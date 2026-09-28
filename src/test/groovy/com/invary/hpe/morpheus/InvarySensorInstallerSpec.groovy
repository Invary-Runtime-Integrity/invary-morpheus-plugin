// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.OsType
import com.morpheusdata.model.PlatformType
import com.morpheusdata.model.TaskResult
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

/**
 * How an installation is chosen, and which nodes are refused before anything on them is
 * touched.
 *
 * Every refusal here has to happen after the probe, which only reads, and before the install
 * script, which stops the service and replaces files. So the assertion that matters in a
 * refusal case is that exactly one command ran.
 */
class InvarySensorInstallerSpec extends Specification {

    /** The commands the installer ran, in order: the probe first, then the install script. */
    List<String> commands = []

    MorpheusContext morpheus = Mock(MorpheusContext)

    static ComputeServer server(String platform = 'linux') {
        return new ComputeServer(id: 4L, name: 'node-4', platform: platform)
    }

    static final String CUSTOMER_PACKAGE_URL = 'https://static.invary.com/package/invary-core-test.tar.gz'

    static InvarySensorConfig config(Map overrides = [:]) {
        return new InvarySensorConfig([
            apiUrl: 'https://appraiser:8443',
            sensorUrl: 'https://appraiser:7386',
            allowUntrusted: true,
            tags: ['hpe.morpheus.server:4'],
            remediationAction: 'none',
            customerPackageUrl: CUSTOMER_PACKAGE_URL,
        ] + overrides)
    }

    /**
     * Stands the customer package in the cache, so that an install is served without anything
     * being fetched. What the archive holds is InvaryCustomerPackageSpec's subject, not this
     * one's - here it only has to be there.
     */
    def setup() {
        InvaryCustomerPackage.clearCache()
        InvaryCustomerPackage.seedCache(CUSTOMER_PACKAGE_URL,
            [deb: 'a deb repo package'.bytes, rpm: 'an rpm repo package'.bytes])
    }

    def cleanup() {
        InvaryCustomerPackage.clearCache()
    }

    /** Answers the probe with the given fields, and any later command with success. */
    void probeReports(String output, String installed = 'Invary Sensor installed') {
        morpheus.executeCommandOnServer(*_) >> { args ->
            commands << (args[1] as String)
            return Single.just(new TaskResult(
                success: true,
                exitCode: '0',
                output: commands.size() == 1 ? output : installed))
        }
    }

    /** Answers the probe with a plain node, and the install with the given transcript. */
    void installReports(String transcript) {
        probeReports(probe(), transcript)
    }

    static String probe(Map fields = [:]) {
        Map all = [arch: 'x86_64', installed: 'no', pkgfmt: 'deb', pkgtool: 'apt-get',
                   shellinstall: 'no', pkgversion: 'none', release: 'none',
                   foreignrepo: 'none', config: ''] + fields
        return all.collect { k, v -> "${k}=${v}" }.join('\n')
    }

    String installScript() {
        return commands[1]
    }

    // -- What runs where ------------------------------------------------------

    def "an x86_64 linux node is installed from the package repository"() {
        given:
        probeReports(probe())

        when:
        def result = new InvarySensorInstaller(morpheus).install(server(), config())

        then:
        result.success
        commands.size() == 2
        installScript().contains('a deb repo package'.bytes.encodeBase64().toString())
        installScript().contains('dpkg -i "$RELPKG"')
    }

    // -- Refusals, all of which leave the node as they found it ---------------

    def "a windows node is refused before it is contacted"() {
        when:
        new InvarySensorInstaller(morpheus).install(server('windows'), config())

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('Windows')
        e.message.contains('Linux servers only')
        commands.isEmpty()
    }

    def "an aarch64 linux node is refused after the probe and before anything is touched"() {
        given:
        probeReports(probe(arch: 'aarch64'))

        when:
        new InvarySensorInstaller(morpheus).install(server(), config())

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('aarch64')
        e.message.contains('x86_64')

        and:
        // the probe ran and nothing else did, so a sensor already on the node is untouched
        commands.size() == 1
    }

    def "a node with no package manager is refused"() {
        given:
        probeReports(probe(pkgfmt: 'none', pkgtool: 'none'))

        when:
        new InvarySensorInstaller(morpheus).install(server(), config())

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('package manager')
        commands.size() == 1
    }

    // -- Channel conflicts ----------------------------------------------------

    def "a node configured for an Invary channel has that repository replaced"() {
        given:
        probeReports(probe(release: 'invary-onprem-release+'))

        when:
        def result = new InvarySensorInstaller(morpheus).install(server(), config())

        then:
        result.success
        // which of them is replaced is settled on the node, once the customer package has been
        // read: its name is the only thing that says which channel it configures
        installScript().contains("for old in 'invary-onprem-release'; do")
        installScript().contains('if [ "$old" = "$REPO_PKG" ]; then')
    }

    def "a node carrying no Invary repository has nothing replaced"() {
        given:
        probeReports(probe())

        when:
        new InvarySensorInstaller(morpheus).install(server(), config())

        then:
        !installScript().contains('replacing the Invary repository')
    }

    // -- Reporting a channel move ---------------------------------------------

    def "a run that moved the machine between channels says so in the result"() {
        given:
        installReports("installing\n${InvarySensorScripts.CHANNEL_MARKER} next -> stable\nInvary Sensor installed")

        when:
        def result = new InvarySensorInstaller(morpheus).install(server(), config())

        then:
        result.success
        result.message.contains('package channel next -> stable')
    }

    def "a run that moved the machine between no channels says nothing about one"() {
        given:
        probeReports(probe())

        when:
        def result = new InvarySensorInstaller(morpheus).install(server(), config())

        then:
        result.success
        !result.message.contains('package channel')
    }

    // -- Clearing the interval ------------------------------------------------

    def "clearing the interval is allowed on linux"() {
        given:
        probeReports(probe())

        when:
        def result = new InvarySensorInstaller(morpheus)
            .install(server(), config(measurementInterval: InvarySensorConfig.INTERVAL_CLEAR))

        then:
        result.success
    }

    // -- Which platform a server reports --------------------------------------

    def "the distribution a server reports as its platform is not read as one"() {
        given:
        // exactly what the Morpheus API reports for these nodes: the distribution in
        // `platform`, the platform in `osType`, and the operating system type only on a
        // server carrying the relation, which not every task is handed
        def ubuntu = new ComputeServer(id: 4L, name: 'web-server-2', platform: 'ubuntu', osType: 'linux')
        def rocky = new ComputeServer(id: 5L, name: 'rocky-8', platform: 'rocky', osType: 'linux')

        expect:
        InvarySensorInstaller.resolveTarget(ubuntu) == InvarySensorInstaller.TARGET_LINUX
        InvarySensorInstaller.resolveTarget(rocky) == InvarySensorInstaller.TARGET_LINUX
    }

    def "the operating system type decides when the server carries one"() {
        given:
        def linux = new ComputeServer(id: 4L, name: 'node-4', platform: 'ubuntu',
            serverOs: new OsType(code: 'ubuntu.24.04.64', platform: PlatformType.linux))
        def windows = new ComputeServer(id: 5L, name: 'node-5',
            serverOs: new OsType(code: 'windows.server.2022', platform: PlatformType.windows))

        expect:
        InvarySensorInstaller.resolveTarget(linux) == InvarySensorInstaller.TARGET_LINUX

        when:
        InvarySensorInstaller.resolveTarget(windows)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('Windows')
    }

    def "a node of neither platform is refused, naming everything it reported"() {
        when:
        InvarySensorInstaller.resolveTarget(
            new ComputeServer(id: 6L, name: 'esx-1', platform: 'esxi', osType: 'esxi'))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('esx-1')
        e.message.contains('esxi')
    }

    def "a node reporting no operating system at all is refused as undetermined"() {
        when:
        // osType stands at `linux` until it is set, so a server describing nothing has to say so
        InvarySensorInstaller.resolveTarget(new ComputeServer(id: 7L, name: 'bare-1', osType: null))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('could not be determined')
    }

    // -- Reporting ------------------------------------------------------------

    def "a converted shell installation is named in the result"() {
        given:
        probeReports(probe(installed: 'yes', shellinstall: 'yes'))

        when:
        def result = new InvarySensorInstaller(morpheus).install(server(), config())

        then:
        result.message.contains('reconfigured')
        result.message.contains('converted from the shell installer')
    }
}
