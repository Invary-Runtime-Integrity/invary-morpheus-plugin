// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import spock.lang.Specification
import spock.lang.Unroll

class InvarySensorScriptsSpec extends Specification {

    static InvarySensorConfig config(Map overrides = [:]) {
        return new InvarySensorConfig([
            apiUrl: 'https://192.0.2.10:8443',
            sensorUrl: 'https://192.0.2.10:7386',
            allowUntrusted: true,
            tags: ['hpe.morpheus.id:23'],
            measurementInterval: 300,
            remediationAction: 'none',
        ] + overrides)
    }

    static String freshConfig(InvarySensorConfig c = config()) {
        return InvarySensorScripts.mergeToml(null, c)
    }

    // -- Merge: the managed keys ----------------------------------------------

    def "an absent configuration is written as the documented tables"() {
        when:
        def merged = InvarySensorScripts.mergeToml(null, config())

        then:
        merged == '''\
tags = ["hpe.morpheus.id:23"]

[connect]
uri = "https://192.0.2.10:7386"
allow.untrusted = true

[measurement]
interval = 300
'''
    }

    def "a blank configuration is treated as absent"() {
        expect:
        InvarySensorScripts.mergeToml('   \n\n  ', config()) == freshConfig()
    }

    def "every managed value is rendered"() {
        when:
        def merged = InvarySensorScripts.mergeToml(null, config(
            allowUntrusted: false,
            measurementInterval: 900,
            tags: ['hpe.morpheus.id:23', 'hpe.morpheus.remediate:revert', 'prod']))

        then:
        merged.contains('tags = ["hpe.morpheus.id:23", "hpe.morpheus.remediate:revert", "prod"]')
        merged.contains('allow.untrusted = false')
        merged.contains('interval = 900')
    }

    def "an install that sets no interval writes none"() {
        when:
        def merged = InvarySensorScripts.mergeToml(null, config(measurementInterval: null))

        then:
        !merged.contains('interval')
        !merged.contains('[measurement]')
        merged.contains('uri = "https://192.0.2.10:7386"')
    }

    def "an install that sets no interval keeps the one a configuration holds"() {
        given:
        def existing = '''\
tags = ["old"]

[connect]
uri = "https://old:7386"

[measurement]
interval = 60
uptime = 120
'''

        when:
        def merged = InvarySensorScripts.mergeToml(existing, config(measurementInterval: null))

        then:
        // operators appraise critical machines more often by pinning a shorter interval on
        // them, so an install with no opinion must not take that away
        merged.contains('interval = 60')
        // a setting the plugin does not own is left where it stood
        merged.contains('uptime = 120')
    }

    def "clearing removes the interval a configuration holds"() {
        given:
        def existing = '''\
tags = ["old"]

[measurement]
interval = 60
uptime = 120
'''

        when:
        def merged = InvarySensorScripts.mergeToml(existing,
            config(measurementInterval: InvarySensorConfig.INTERVAL_CLEAR))

        then:
        !merged.contains('interval')
        !merged.contains('null')
        merged.contains('uptime = 120')
    }

    def "clearing removes an interval written as a dotted key"() {
        given:
        def existing = 'measurement.interval = 60\ntags = ["old"]\n'

        when:
        def merged = InvarySensorScripts.mergeToml(existing,
            config(measurementInterval: InvarySensorConfig.INTERVAL_CLEAR))

        then:
        !merged.contains('interval')
    }

    def "clearing removes every assignment of the interval"() {
        given:
        def existing = '''\
[measurement]
interval = 60
interval = 90
'''

        when:
        def merged = InvarySensorScripts.mergeToml(existing,
            config(measurementInterval: InvarySensorConfig.INTERVAL_CLEAR))

        then:
        !merged.contains('interval')
    }

    def "clearing an interval a configuration does not hold writes nothing"() {
        when:
        def merged = InvarySensorScripts.mergeToml(null,
            config(measurementInterval: InvarySensorConfig.INTERVAL_CLEAR))

        then:
        !merged.contains('interval')
        !merged.contains('[measurement]')
        merged.contains('uri = "https://192.0.2.10:7386"')
    }

    def "an install that sets an interval rewrites the one a configuration holds"() {
        given:
        def existing = '[measurement]\ninterval = 60\n'

        when:
        def merged = InvarySensorScripts.mergeToml(existing, config(measurementInterval: 300))

        then:
        merged.contains('interval = 300')
        !merged.contains('interval = 60')
    }

    def "a kept managed key keeps every line of a multi line value"() {
        given:
        // tags is set by every install, so the key left alone here is the interval; the array
        // proves the guard runs before the lines of a value are consumed
        def existing = '''\
[measurement]
interval = [
    60,
]
uptime = 120
'''

        when:
        def merged = InvarySensorScripts.mergeToml(existing, config(measurementInterval: null))

        then:
        merged.contains('interval = [')
        merged.contains('60,')
        merged.contains(']')
        merged.contains('uptime = 120')
    }

    def "quotes and backslashes in tags are escaped"() {
        when:
        def merged = InvarySensorScripts.mergeToml(null, config(tags: ['a"b', 'c\\d']))

        then:
        merged.contains('tags = ["a\\"b", "c\\\\d"]')
    }

    // -- Merge: a table is never declared twice -------------------------------

    def "a configuration this install would not change comes back unchanged"() {
        given:
        // what the installer writes, with the values this install sets
        def existing = '''\
tags = ["rocky-9", "hpe.morpheus.id:23"]

[connect]
uri = "https://192.0.2.10:7386"
allow.untrusted = true

[measurement]
interval = 300
'''

        expect:
        InvarySensorScripts.mergeToml(existing, config()) == existing
    }

    @Unroll
    def "a managed key is rewritten where it stands, under #description"() {
        when:
        def merged = InvarySensorScripts.mergeToml(existing, config())

        then:
        !merged.contains('SHOULD_NOT_SURVIVE')
        merged.contains(rewritten)
        // the table it stands under is declared once, by its header alone
        merged.count('[connect]') == 1
        !merged.contains('connect.allow.untrusted =')

        where:
        description          | existing                                                  | rewritten
        '[connect]'          | '[connect]\nallow.untrusted = "SHOULD_NOT_SURVIVE"\n'      | '[connect]\nallow.untrusted = true'
        'a quoted key'       | '[connect]\n"allow"."untrusted" = "SHOULD_NOT_SURVIVE"\n'  | '"allow"."untrusted" = true'
        'a spaced assignment'| '[connect]\n  allow.untrusted   =   "SHOULD_NOT_SURVIVE"\n'| '  allow.untrusted = true'
    }

    def "a key under a sub table is rewritten there rather than under its parent"() {
        when:
        def merged = InvarySensorScripts.mergeToml('[connect.allow]\nuntrusted = false\n', config())

        then:
        merged.contains('[connect.allow]\nuntrusted = true')
        // writing allow.untrusted under [connect] would declare connect.allow a second time
        !merged.contains('allow.untrusted')
    }

    def "a top level dotted key is rewritten in place, and its siblings join it there"() {
        when:
        def merged = InvarySensorScripts.mergeToml('connect.allow.untrusted = false\n', config())

        then:
        merged.contains('connect.allow.untrusted = true')
        // the dotted key has declared connect, so a header for it would declare it twice
        merged.contains('connect.uri = "https://192.0.2.10:7386"')
        !merged.contains('[connect]')
    }

    def "every managed key of an installer written configuration is updated in place"() {
        given:
        def existing = '''\
tags = ["old"]

[connect]
uri = "https://old:7386"
allow.untrusted = false

[measurement]
interval = 60
profile = "core"
'''

        when:
        def merged = InvarySensorScripts.mergeToml(existing, config())

        then:
        merged == '''\
tags = ["old", "hpe.morpheus.id:23"]

[connect]
uri = "https://192.0.2.10:7386"
allow.untrusted = true

[measurement]
interval = 300
profile = "core"
'''
    }

    // -- Merge: preserving the operator's settings ----------------------------

    def "unmanaged settings and comments survive verbatim"() {
        given:
        def existing = '''\
# our own notes about this host
[limit]
cpu = 1.0

[log]
verbosity = 3

[measurement]
profile = "detailed"
initial.delay = 45
'''

        when:
        def merged = InvarySensorScripts.mergeToml(existing, config())

        then:
        merged == '''\
# our own notes about this host
tags = ["hpe.morpheus.id:23"]

[limit]
cpu = 1.0

[log]
verbosity = 3

[measurement]
profile = "detailed"
initial.delay = 45
interval = 300

[connect]
uri = "https://192.0.2.10:7386"
allow.untrusted = true
'''
    }

    def "an array of tables is left as it was written"() {
        when:
        def merged = InvarySensorScripts.mergeToml('tags = ["keep"]\n\n[[probe]]\nname = "a"\n', config())

        then:
        merged.contains('[[probe]]\nname = "a"')
        // a table written after it starts a table of its own, where a dotted key would not
        merged.indexOf('[[probe]]') < merged.indexOf('[connect]')
    }

    def "a multi line array is replaced by one line"() {
        given:
        def existing = '''\
tags = [
    "one",
    "two",
]

[limit]
cpu = 2.0
'''

        when:
        def merged = InvarySensorScripts.mergeToml(existing, config())

        then:
        merged.contains('tags = ["one", "two", "hpe.morpheus.id:23"]')
        merged.contains('cpu = 2.0')
    }

    def "a bracket inside a string does not confuse array consumption"() {
        given:
        def existing = 'tags = ["a]b"]\n\n[limit]\ncpu = 2.0\n'

        when:
        def merged = InvarySensorScripts.mergeToml(existing, config())

        then:
        merged.contains('tags = ["a]b", "hpe.morpheus.id:23"]')
        merged.contains('cpu = 2.0')
    }

    def "the comment an earlier configuration was marked with is removed"() {
        given:
        def marker = '# Managed by the Invary Morpheus plugin - edits to these keys will be overwritten'

        when:
        def merged = InvarySensorScripts.mergeToml("${marker}\ntags = [\"old\"]\n", config())

        then:
        !merged.contains(marker)
    }

    @Unroll
    def "merging is idempotent over #description"() {
        when:
        def once = InvarySensorScripts.mergeToml(existing, config())
        def twice = InvarySensorScripts.mergeToml(once, config())

        then:
        once == twice

        where:
        description             | existing
        'nothing'               | null
        'unmanaged tables'      | '[limit]\ncpu = 1.0\n'
        'an installer config'   | 'tags = ["old"]\n\n[connect]\nuri = "https://old"\nallow.untrusted = false\n'
        'a sub table'           | '[connect.allow]\nuntrusted = false\n'
        'an array of tables'    | 'tags = ["x"]\n\n[[probe]]\nname = "a"\n'
    }

    // -- Merge: tags ----------------------------------------------------------

    @Unroll
    def "merging tags #description"() {
        expect:
        InvarySensorScripts.mergeTags(existing, installed) == merged

        where:
        description                        | existing                                          | installed                        | merged
        'keeps what the operator added'    | ['rocky-9']                                       | ['hpe.morpheus.server:20']       | ['rocky-9', 'hpe.morpheus.server:20']
        'keeps them where they stood'      | ['rocky-9', 'hpe.morpheus.server:20']             | ['hpe.morpheus.server:20']       | ['rocky-9', 'hpe.morpheus.server:20']
        'replaces a tag it owns'           | ['rocky-9', 'hpe.morpheus.instance:18']           | ['hpe.morpheus.instance:19']     | ['rocky-9', 'hpe.morpheus.instance:19']
        'drops one it no longer sets'      | ['hpe.morpheus.remediate:revert', 'prod']         | ['hpe.morpheus.server:20']       | ['prod', 'hpe.morpheus.server:20']
        'adds to nothing'                  | []                                                | ['hpe.morpheus.server:20']       | ['hpe.morpheus.server:20']
        'adds nothing twice'               | ['hpe.morpheus.server:20']                        | ['hpe.morpheus.server:20']       | ['hpe.morpheus.server:20']
    }

    def "the tags a configuration holds are read back whatever their quoting"() {
        expect:
        InvarySensorScripts.tomlStrings('["a", \'b\', "c\\"d", "e\\\\f"]') == ['a', 'b', 'c"d', 'e\\f']
    }

    // -- Probes ---------------------------------------------------------------

    def "a linux probe reports architecture, install state and configuration"() {
        given:
        def encoded = '[limit]\ncpu = 1.0\n'.bytes.encodeBase64().toString()

        when:
        def probe = InvarySensorScripts.parseProbe("arch=x86_64\ninstalled=yes\nconfig=${encoded}")

        then:
        probe.arch == 'x86_64'
        probe.installed
        probe.config == '[limit]\ncpu = 1.0\n'
    }

    def "a probe reported as a map of process state is still readable"() {
        given:
        // some transports report a command's result as a rendered map rather than plain output
        def output = '[success:true, exited:true, exitCode:0, output:arch=x86_64\ninstalled=no\nconfig=\n]'

        when:
        def probe = InvarySensorScripts.parseProbe(output)

        then:
        probe.arch == 'x86_64'
        !probe.installed
        probe.config == null
    }

    def "a probe that reported nothing usable quotes back what was seen"() {
        when:
        InvarySensorScripts.parseProbe('bash: uname: command not found')

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('bash: uname: command not found')
    }

    def "a probe that reported nothing at all says so"() {
        when:
        InvarySensorScripts.parseProbe(null)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('(nothing)')
    }

    def "an absent configuration comes back as null"() {
        when:
        def probe = InvarySensorScripts.parseProbe('arch=x86_64\ninstalled=no\nconfig=')

        then:
        !probe.installed
        probe.config == null
    }

    @Unroll
    def "architecture #reported maps to #expected"() {
        expect:
        InvarySensorScripts.packageArch(reported) == expected

        where:
        reported   || expected
        'x86_64'   || 'x86_64'
        'AMD64'    || 'x86_64'
        'amd64'    || 'x86_64'
        'aarch64'  || 'aarch64'
        'arm64'    || 'aarch64'
        'ARM64'    || 'aarch64'
        ' x86_64 ' || 'x86_64'
    }

    @Unroll
    def "architecture #reported is rejected"() {
        when:
        InvarySensorScripts.packageArch(reported)

        then:
        thrown(IllegalArgumentException)

        where:
        reported << ['i686', 'armv7l', 'ppc64le', '', null]
    }

    // -- What every linux script does -----------------------------------------

    def "the stop waits for the sensor to be gone rather than for systemctl to return"() {
        when:
        def script = packageScript()

        then:
        script.contains('state=$(systemctl show -p ActiveState --value invary 2>/dev/null || true)')
        // a unit that keeps restarting reports neither inactive nor failed
        script.contains('inactive|failed|\'\')')
        // matched on the command line, since the sensor renames its process
        script.contains('pgrep -f "^/opt/invary/bin/invary( |$)"')
        script.contains('while [ "$waited" -lt 30 ]; do')
        script.contains('The invary service was still running 30 seconds after being stopped')
    }

    def "the linux script backs up and restores the configuration on failure"() {
        when:
        def script = packageScript()

        then:
        script.contains('BAK="$CFG.morpheus-$STAMP.bak"')
        script.contains('trap restore ERR')
        // the restore only fires when a backup was actually taken
        script.contains('if [ -n "$BAK" ] && [ -f "$BAK" ]; then')
        script.contains('systemctl start invary >/dev/null 2>&1 || true')
    }

    def "the linux script puts the sensor back at the version it was running on failure"() {
        when:
        def script = packageScript()

        then:
        script.contains('BIN=/opt/invary/bin/invary')
        script.contains('BINBAK="$BIN.morpheus-$STAMP.bak"')
        // the backup is taken before the install replaces the binary
        script.indexOf('cp -a "$BIN" "$BINBAK"') < script.indexOf('apt-get install -y invary')
        script.contains('if [ -n "$BINBAK" ] && [ -f "$BINBAK" ]; then')
        script.contains('cp -a "$BINBAK" "$BIN"')
        // the file a process is executing cannot be written to
        script.indexOf('stop_sensor || true') < script.indexOf('cp -a "$BINBAK" "$BIN"')
        // a copy of the binary is too large to leave behind
        script.contains('if [ -n "$BINBAK" ]; then\n        rm -f "$BINBAK"\n    fi')
    }

    def "a run with nothing to put back leaves the node without a sensor running"() {
        when:
        def script = packageScript()

        then:
        script.contains('if [ -n "$BINBAK" ] || [ -n "$BAK" ]; then\n        systemctl start invary')
    }

    def "the linux script names the step it is running"() {
        when:
        def script = packageScript()

        then:
        script.contains('STEP="$1"')
        script.contains('step "stopping the invary service"')
        script.contains('step "installing the invary package from the $NEW_CHANNEL channel"')
        script.contains('step "checking the sensor configuration"')
        script.contains('step "writing $CFG"')
        script.contains('step "starting the invary service"')
    }

    def "a failed linux run reports the step, the service and what it logged"() {
        when:
        def script = packageScript()

        then:
        // both streams arrive together, so a failing command's output sits with its step
        script.contains('exec 2>&1')
        script.contains('The Invary Sensor install failed while $STEP (exit $rc)')
        script.contains('systemctl status invary --no-pager --full || true')
        script.contains('journalctl -u invary --no-pager --full --lines=50 || true')
        // a sensor that keeps exiting is restarted, which reads as activating rather than failed
        script.contains('--property=LoadState,ActiveState,SubState,Result,ExecMainStatus,ExecMainCode,NRestarts,ExecStart')
        script.contains('tail_file "$CFG" 200')
        script.contains("${InvarySensorScripts.FAILURE_MARKER} exit=\$rc step=\$STEP")
        // the diagnostics report a stopped service by failing, which must not re-enter the
        // handler, and must not stop the marker from being written either
        script.contains('trap - ERR')
        script.contains('diagnostics || true')
    }

    def "a failed linux run reports what the sensor itself logged"() {
        when:
        def script = packageScript()

        then:
        // the sensor logs to a file rather than to stderr, so the journal holds none of it
        script.contains('ls -l /var/opt/invary || true')
        script.contains('for f in /var/opt/invary/*.err /var/opt/invary/*.out /var/opt/invary/*.log; do')
        script.contains('tail_file "$f" 50')
        // a missing file is reported as missing rather than as an empty section
        script.contains('echo "(no such file)"')
    }

    def "a failed linux run summarizes the cause where it cannot be elided"() {
        when:
        def script = packageScript()

        then:
        script.contains("echo \"${InvarySensorScripts.SUMMARY_MARKER}\"")
        // the summary is written last, so a message elided in the middle still carries it
        script.indexOf('summary || true') < script.indexOf(InvarySensorScripts.FAILURE_MARKER + ' exit=')
        // a sensor that fails while loading its configuration reports it only to the journal
        script.contains('journalctl -u invary --no-pager --lines=12')
        script.contains("grep -aiE '${InvarySensorScripts.CAUSE_PATTERN}'")
        // a log left by an earlier run says nothing about this one
        script.contains("date -r \"\$f\"")
    }

    def "the summary describes the failure rather than the sensor put back after it"() {
        when:
        def script = packageScript()

        then:
        script.indexOf('summary || true') < script.indexOf('cp -a "$BAK" "$CFG"')
    }

    @Unroll
    def "a summary is read back from a transcript, ignoring #ignored"() {
        expect:
        InvarySensorScripts.parseSummary(transcript) == summary

        where:
        ignored          | transcript                                                                     | summary
        'the diagnostics'| 'noise\ninvary-install-summary:\nExecMainStatus=1\ninvary-install-failed: exit=3 step=x' | 'ExecMainStatus=1'
        'a missing end'  | 'invary-install-summary:\nExecMainStatus=1\n'                                   | 'ExecMainStatus=1'
        'nothing said'   | 'invary-install-summary:\n\ninvary-install-failed: exit=3 step=x'               | null
        'no summary'     | 'invary-install-failed: exit=3 step=x'                                          | null
    }

    def "a started sensor is watched for long enough to see it go"() {
        when:
        def script = packageScript()

        then:
        // being up is not what says an install worked, since a sensor that is killed and
        // restarted is up whenever it is sampled
        script.contains('systemctl show -p NRestarts --value invary')
        script.contains('while [ "$waited" -lt 10 ]; do')
        script.contains('The invary service stopped $waited seconds after it was started')
        script.contains('The invary service was restarted $waited seconds after it was started')
        // the watch is the last word on whether the install worked
        script.indexOf('systemctl start invary\n') < script.indexOf('\nwatch_sensor\n')
    }

    @Unroll
    def "a failure is read back from a transcript ending #ending"() {
        expect:
        def failure = InvarySensorScripts.parseFailure(transcript)
        failure?.exitCode == exitCode
        failure?.step == step

        where:
        ending      | transcript                                                                 | exitCode | step
        'a line'    | 'starting\ninvary-install-failed: exit=3 step=starting the invary service\n' | '3'      | 'starting the invary service'
        'the text'  | 'invary-install-failed: exit=1 step=unpacking the Invary Sensor'             | '1'      | 'unpacking the Invary Sensor'
        'in a map'  | '[success:false, output:invary-install-failed: exit=7 step=downloading]'      | '7'      | 'downloading'
    }

    def "a transcript naming no failure reads back as none"() {
        expect:
        InvarySensorScripts.parseFailure(transcript) == null

        where:
        transcript << [null, '', 'Invary Sensor installed']
    }

    def "what the linux run downloaded is removed however it ends"() {
        when:
        def script = packageScript()

        then:
        // an EXIT trap covers failure as well as success, and the restore path exits through it
        script.contains('trap cleanup EXIT')
        script.contains('rm -f "$RELPKG"')
        script.contains('rm -f "$UNITBAK"')
        // cleanup leaves any directory the run is sitting in before removing anything
        script.contains('cleanup() {\n    cd /')
    }

    // -- Probe: the package fields --------------------------------------------

    static InvarySensorScripts.Probe probe(Map overrides = [:]) {
        return new InvarySensorScripts.Probe([
            arch: 'x86_64',
            installed: false,
            config: null,
            pkgFmt: 'deb',
            pkgTool: 'apt-get',
            shellInstall: false,
            pkgVersion: null,
            repoPackages: [],
            foreignRepos: [],
        ] + overrides)
    }

    def "a probe reports the package manager, the shell install and the invary packages"() {
        when:
        def parsed = InvarySensorScripts.parseProbe('arch=x86_64\ninstalled=yes\npkgfmt=rpm\n' +
            'pkgtool=dnf\nshellinstall=yes\npkgversion=26.8.1-1\n' +
            'release=invary-onprem-release-next+\nforeignrepo=/etc/yum.repos.d/mine.repo+\nconfig=')

        then:
        parsed.pkgFmt == 'rpm'
        parsed.pkgTool == 'dnf'
        parsed.shellInstall
        parsed.pkgVersion == '26.8.1-1'
        parsed.repoPackages == ['invary-onprem-release-next']
        parsed.foreignRepos == ['/etc/yum.repos.d/mine.repo']
    }

    def "a probe reports every repo package, not only the first"() {
        when:
        def parsed = InvarySensorScripts.parseProbe(
            'arch=x86_64 installed=no release=invary-onprem-release+invary-onprem-release-next+')

        then:
        parsed.repoPackages == ['invary-onprem-release', 'invary-onprem-release-next']
    }

    def "a probe reporting none for a package field reports nothing"() {
        when:
        def parsed = InvarySensorScripts.parseProbe('arch=x86_64\ninstalled=no\npkgfmt=none\n' +
            'pkgtool=none\npkgversion=none\nrelease=none\nforeignrepo=none\n')

        then:
        parsed.pkgFmt == null
        parsed.pkgTool == null
        parsed.pkgVersion == null
        parsed.repoPackages.isEmpty()
        parsed.foreignRepos.isEmpty()
    }

    def "the package fields survive a transport that renders output as a map"() {
        when:
        def parsed = InvarySensorScripts.parseProbe(
            '[success:true, output:arch=x86_64 installed=no pkgfmt=deb pkgtool=apt-get shellinstall=yes]')

        then:
        parsed.pkgFmt == 'deb'
        parsed.pkgTool == 'apt-get'
        parsed.shellInstall
    }

    def "the linux probe asks for every field the install needs"() {
        when:
        def script = InvarySensorScripts.linuxProbe()

        then:
        ['arch=', 'installed=', 'config=', 'pkgfmt=', 'pkgtool=',
         'shellinstall=', 'pkgversion=', 'release=', 'foreignrepo='].every { script.contains(it) }

        and:
        // the package manager decides, and nothing reads /etc/os-release to decide it
        script.contains('command -v dnf')
        script.contains('command -v apt-get')
        !script.contains('. /etc/os-release')
    }

    // -- The package install script -------------------------------------------

    /** Stands in for the repo package the appliance read out of the customer package. */
    static final byte[] REPO_PACKAGE = 'a repo definition package'.bytes

    static String packageScript(Map probeOverrides = [:], Map configOverrides = [:]) {
        def c = config(configOverrides)
        return InvarySensorScripts.linuxPackageScript(c, probe(probeOverrides),
            InvarySensorScripts.mergeToml(null, c), REPO_PACKAGE)
    }

    def "the repo package is written from the customer package rather than downloaded"() {
        when:
        def script = packageScript()

        then:
        script.contains('base64 -d > "$RELPKG"')
        script.contains(REPO_PACKAGE.encodeBase64().toString())
        // nothing on the node reaches out for it: the appliance already has it
        !script.contains('curl')
        !script.contains('packages.invary.com/install')
    }

    @Unroll
    def "the #fmt script reads the repo package name rather than assuming it"() {
        when:
        def script = packageScript(pkgFmt: fmt, pkgTool: tool)

        then:
        script.contains("REPO_PKG=\$(${discovery}")
        script.contains('NEW_CHANNEL=$(channel_of "$REPO_PKG")')
        // a customer package may carry the release, next or staging package under one file name
        !script.contains('invary-onprem-release-next')

        where:
        fmt   | tool      || discovery
        'deb' | 'apt-get' || 'dpkg-deb -f "$RELPKG" Package'
        'rpm' | 'dnf'     || 'rpm -qp --qf \'%{NAME}\' "$RELPKG"'
    }

    def "the deb script installs the repo package and refreshes only its own source"() {
        when:
        def script = packageScript()

        then:
        script.contains('dpkg -i "$RELPKG"')
        script.contains('dpkg -L "$REPO_PKG"')
        script.contains('Dir::Etc::sourcelist="$LIST_FILE"')
        script.contains('Dir::Etc::sourceparts=/dev/null')
        script.contains('apt-get install -y --allow-downgrades invary="$TARGET"')
    }

    def "the rpm script imports the key after installing the repo package and verifies it"() {
        when:
        def script = packageScript(pkgFmt: 'rpm', pkgTool: 'dnf')

        then:
        script.indexOf('rpm -Uvh --replacepkgs') < script.indexOf('rpm --import')
        script.contains('rpm -Uvh --replacepkgs "$RELPKG"')
        script.contains('rpm --import /etc/pki/rpm-gpg/RPM-GPG-KEY-invary')
        script.contains('grep -qix a62871d5')
    }

    def "the rpm script discovers the repository rather than naming it"() {
        when:
        def script = packageScript(pkgFmt: 'rpm', pkgTool: 'dnf')

        then:
        script.contains('rpm -ql "$REPO_PKG"')
        script.contains('REPO_ID=$(sed -n')
        script.contains('makecache --refresh --repo "$REPO_ID"')

        and:
        // the id the repo package happens to use is never assumed
        !script.contains('invary-onprem-next')
    }

    def "yum gets the makecache spelling it understands"() {
        when:
        def script = packageScript(pkgFmt: 'rpm', pkgTool: 'yum')

        then:
        script.contains('''yum -y -q --disablerepo='*' --enablerepo="$REPO_ID" makecache''')
        script.contains('yum -y --setopt=*.skip_if_unavailable=True install invary')
        !script.contains('makecache --refresh --repo')
    }

    def "the package script never holds the service"() {
        expect:
        // neither package manager starts the sensor, so there is nothing to refuse
        !packageScript().contains('RefuseManualStart')
        !packageScript(pkgFmt: 'rpm', pkgTool: 'dnf').contains('RefuseManualStart')
    }

    def "the package script checks the configuration between writing it and starting"() {
        when:
        def script = packageScript()

        then:
        script.indexOf('INVARY_TOML_B64') < script.indexOf('/opt/invary/bin/invary check')
        // the step, not the start in the restore handler above it
        script.indexOf('/opt/invary/bin/invary check') < script.indexOf('step "starting the invary service"')
    }

    def "the shell installation is converted only when the probe found one"() {
        expect:
        !packageScript().contains('converting the shell installation')

        when:
        def script = packageScript(shellInstall: true)

        then:
        script.contains('converting the shell installation')
        script.contains('systemctl disable --now invary.service')
        script.contains('rm -f /etc/systemd/system/invary.service /etc/init.d/invary')
        script.contains('rm -f /opt/invary/bin/invary')
        script.contains('CONVERTED=yes')

        and:
        // the sensor's identity lives here, and losing it means provisioning the machine again
        !script.contains('rm -rf /var/opt/invary')
        !script.contains('invary-uninstall')
    }

    def "nothing is replaced on a machine carrying no Invary repository"() {
        expect:
        !packageScript().contains('replacing the Invary repository')
    }

    @Unroll
    def "the #fmt script replaces the repo package the machine carries"() {
        when:
        def script = packageScript(pkgFmt: fmt, pkgTool: tool, repoPackages: ['invary-onprem-release'])

        then:
        script.contains('replacing the Invary repository')
        script.contains("for old in 'invary-onprem-release'; do")
        // whichever package the customer package turned out to hold is the one kept
        script.contains('if [ "$old" = "$REPO_PKG" ]; then')
        script.contains(remove)

        and:
        // the files it owns are kept, so a failed run can put the machine back on its channel
        script.contains("${list} \"\$old\"")
        script.contains('cp -a --parents "$owned_file" "$REPOBAK"')

        where:
        fmt   | tool      || remove              | list
        'deb' | 'apt-get' || 'dpkg -r "$old"'    | 'dpkg -L'
        'rpm' | 'dnf'     || 'rpm -e "$old"'     | 'rpm -ql'
    }

    def "an Invary repository file no package owns is removed too"() {
        when:
        def script = packageScript(foreignRepos: ['/etc/yum.repos.d/mine.repo'])

        then:
        script.contains("for unowned in '/etc/yum.repos.d/mine.repo'; do")
        script.contains('rm -f "$unowned"')
        script.contains('REPO_SWAPPED=yes')

        and:
        // kept as well, so the rollback puts back everything the run took away
        script.contains('cp -a --parents "$unowned" "$REPOBAK"')
    }

    def "the installed version is checked against the channel before the config is written"() {
        when:
        def script = packageScript()

        then:
        script.contains('verifying the installed version comes from the $NEW_CHANNEL channel')
        script.contains('apt-cache madison invary')
        // the source is read off the list file, since the channel is not known until then
        script.contains('LIST_URL=$(awk')
        script.contains('index($3, url)')
        script.indexOf('verifying the installed version') < script.indexOf('INVARY_TOML_B64')
    }

    @Unroll
    def "the #fmt script moves the sensor onto the channel rather than leaving it newer"() {
        when:
        def script = packageScript(pkgFmt: fmt, pkgTool: tool)

        then:
        script.contains(sync)
        script.indexOf(sync) < script.indexOf('verifying the installed version')

        where:
        fmt   | tool      || sync
        // a plain install is a no-op when the machine carries something newer than the channel has
        'deb' | 'apt-get' || 'apt-get install -y --allow-downgrades invary="$TARGET"'
        'rpm' | 'dnf'     || 'distro-sync invary'
        'rpm' | 'yum'     || 'distro-sync invary'
    }

    // -- Reporting a channel move ---------------------------------------------

    def "a channel is named by the repo package that carries it"() {
        when:
        def script = packageScript()

        then:
        script.contains('*-release)   echo stable ;;')
        script.contains('*-release-*) echo "${1##*-release-}" ;;')
    }

    def "a run that replaces a repo package reports the move it made"() {
        when:
        def script = packageScript(repoPackages: ['invary-onprem-release-next'])

        then:
        script.contains('Invary package channel changed: $OLD_CHANNEL -> $NEW_CHANNEL')
        script.contains("echo \"${InvarySensorScripts.CHANNEL_MARKER} \$OLD_CHANNEL -> \$NEW_CHANNEL\"")

        and:
        // only when one was actually replaced, so a re-run onto the same channel stays quiet
        script.contains('if [ -n "$OLD_REPO_PKG" ]; then')
    }

    def "a run with no repository to replace reports no move"() {
        expect:
        !packageScript().contains(InvarySensorScripts.CHANNEL_MARKER)
    }

    @Unroll
    def "a channel move is read back from a transcript #described"() {
        expect:
        InvarySensorScripts.parseChannelChange(transcript) == expected

        where:
        described                    | transcript                                          || expected
        'naming one'                 | "ok\ninvary-install-channel: next -> stable\ndone"   || 'next -> stable'
        'naming none'                | 'ok\ndone'                                           || null
        'that is empty'              | ''                                                   || null
        'reported as process state'  | '[success:true, output:invary-install-channel: staging -> next]' || 'staging -> next'
        'spaced oddly'               | 'invary-install-channel:   next   ->   stable  '     || 'next -> stable'
    }

    def "the previous repository is put back when a run fails"() {
        when:
        def script = packageScript(repoPackages: ['invary-onprem-release-next'])

        then:
        script.contains('if [ "$REPO_SWAPPED" = yes ]; then')
        script.contains('cp -a "$REPOBAK"/. /')
        script.contains('Restored the previous Invary package repository (${NEW_CHANNEL:-unknown} -> ${OLD_CHANNEL:-unknown})')
        script.contains('The previous Invary package repository could not be restored from $REPOBAK')

        and:
        // the package installed over the old one goes first, or the machine keeps two repositories
        script.indexOf('dpkg -r "$REPO_PKG"') < script.indexOf('cp -a "$REPOBAK"/. /')

        and:
        // put back before the failure marker, so the transcript carries what was undone
        script.indexOf('Restored the previous Invary package repository') <
            script.indexOf(InvarySensorScripts.FAILURE_MARKER + ' exit=')
    }

    def "the repo package and the backup of the one it replaced are always removed"() {
        when:
        def script = packageScript(repoPackages: ['invary-onprem-release-next'])

        then:
        // both carry the repository password, so neither outlives the run
        script.contains('cleanup() {')
        script.contains('rm -f "$RELPKG"')
        script.contains('rm -rf "$REPOBAK"')
        script.contains('chmod 0600 "$RELPKG"')
        script.contains('chmod 0700 "$REPOBAK"')
    }

    def "no repository file is ever reported in the diagnostics"() {
        when:
        def script = packageScript()

        then:
        // they carry the repository password and the transcript is stored as task history
        !script.contains('tail_file "$REPO_FILE"')
        !script.contains('cat "$REPO_FILE"')
        !script.contains('tail_file "$LIST_FILE"')

        and:
        // only the sensor's own configuration and logs are reported
        script.contains('tail_file "$CFG"')
    }

    def "both formats tell an unreadable listing from one that lacks the version"() {
        given:
        // apt-get and dnf both SUCCEED when a newer sensor from elsewhere is already
        // installed, so an empty result here is the failure this step exists for -- not a
        // reason to skip the check
        def deb = packageScript()
        def rpm = packageScript(pkgFmt: 'rpm', pkgTool: 'dnf')

        expect:
        deb.contains('if [ -z "$LISTING" ]')
        rpm.contains('if [ -z "$LISTING" ]')

        and:
        !deb.contains('if [ -z "$OFFERED" ]')
        !rpm.contains('if [ -z "$OFFERED" ]')
    }

    def "an unreachable repository the customer owns does not fail the install"() {
        given:
        // observed on a live node: a stale `morpheus` repo pointing at an unroutable address
        // timed out, and dnf failed the whole transaction before it considered our package
        def rpm = packageScript(pkgFmt: 'rpm', pkgTool: 'dnf')
        def yum = packageScript(pkgFmt: 'rpm', pkgTool: 'yum')

        expect:
        rpm.contains('dnf -y --setopt=*.skip_if_unavailable=True install invary')
        yum.contains('yum -y --setopt=*.skip_if_unavailable=True install invary')

        and:
        // the distribution repositories stay enabled: the sensor package depends on glibc,
        // elfutils-libelf and zlib, which come from them
        !rpm.contains("--disablerepo='*' --enablerepo=\"\$REPO_ID\" install")

        and:
        // apt resolves against the lists already on disk, so it cannot fail this way
        packageScript().contains('apt-get install -y invary')
    }

    def "an unreadable version listing reports rather than fails"() {
        when:
        def script = packageScript(pkgFmt: 'rpm', pkgTool: 'dnf')

        then:
        script.contains('Could not read which versions')
        script.contains('list --showduplicates invary')
    }

    def "every rpm command that can be asked to import the key is told to accept it"() {
        given:
        // repo_gpgcheck makes dnf import into its own per repository keyring, and it prompts
        // before doing so. A prompt that reads EOF fails as a missing signing key, which reads
        // as a broken repository rather than as a declined import
        def script = packageScript(pkgFmt: 'rpm', pkgTool: 'dnf')

        expect:
        script.readLines().findAll { it.contains('dnf ') && !it.trim().startsWith('#') }
            .every { it.contains('dnf -y') }
    }

    def "an installed version is only counted when the repository accounts for it"() {
        when:
        def script = packageScript(pkgFmt: 'rpm', pkgTool: 'dnf')

        then:
        // dnf marks an installed version's origin with a leading @, and counting every version
        // listed would let a sensor installed from elsewhere verify against its own entry
        script.contains('sub(/^@/, "", origin)')
        script.contains('if (origin == repo)')

        and:
        // an empty listing is not the same as a listing that does not carry the version
        script.contains('if [ -z "$LISTING" ]')
    }

    def "rpm versions are compared as EVR so an epoch cannot break the match"() {
        when:
        def script = packageScript(pkgFmt: 'rpm', pkgTool: 'dnf')

        then:
        script.contains("""rpm -q --qf '%{EVR}' invary""")
        !script.contains('%{VERSION}-%{RELEASE}')
    }

    def "the linux script reports a failure by marker, summary and restore"() {
        given:
        def packaged = packageScript()

        expect:
        packaged.contains(InvarySensorScripts.FAILURE_MARKER)
        packaged.contains(InvarySensorScripts.SUMMARY_MARKER)
        packaged.contains('trap restore ERR')
        packaged.contains('watch_sensor')
    }

    def "a failed metadata refresh is repeated without being silenced"() {
        when:
        def script = packageScript(pkgFmt: 'rpm', pkgTool: 'dnf')

        then:
        script.contains('if ! dnf -y -q makecache')
        script.contains('    dnf -y makecache --refresh --repo "$REPO_ID" || true')
    }

    def "the service is enabled when the package did not enable it"() {
        given:
        // reproduced on a node: install, remove without purging, install again leaves the
        // unit disabled, because the package cannot tell that reinstall from an upgrade
        def script = packageScript()

        expect:
        script.contains('if ! systemctl is-enabled invary >/dev/null 2>&1; then')
        script.contains('systemctl enable invary')

        and:
        // after the install, so it observes what the package actually did
        script.indexOf('apt-get install -y invary') < script.indexOf('systemctl enable invary')
    }

    def "an unreachable appraiser does not fail the install"() {
        given:
        // verified on a node: the sensor starts, stays up and never restarts with the
        // appraiser refusing connections, so it recovers on its own once one answers
        def script = packageScript()

        expect:
        script.contains('if [ "$CHECK_RC" = 6 ]')
        script.contains('cannot reach the appraiser at https://192.0.2.10:7386')

        and:
        // the start still happens -- the connectivity branch does not exit
        script.indexOf('CHECK_RC') < script.indexOf('step "starting the invary service"')
    }

    def "a structural check failure stops the install and says which one"() {
        when:
        def script = packageScript()

        then:
        script.contains('exit $CHECK_RC')
        script.contains('2)     echo "The Invary Sensor rejected the configuration')
        script.contains('3|4|5) echo "This machine cannot give the Invary Sensor')
        script.contains('78)    echo "More than one check failed')
    }

    def "a rollback removes the package but never purges it"() {
        given:
        def rpm = packageScript([pkgFmt: 'rpm', pkgTool: 'dnf', shellInstall: true])
        def deb = packageScript([shellInstall: true])

        expect:
        // purging deletes the config and /var/opt/invary, which holds the sensor's identity
        rpm.contains('INVARY_PURGE=0 rpm -e invary')
        deb.contains('dpkg -r invary')

        and:
        !rpm.contains('rpm -e --purge')
        !deb.contains('dpkg -P invary')
        !deb.contains('apt-get purge')
    }

    def "the configuration is written readable only by root"() {
        expect:
        packageScript().contains('chmod 0600 "$CFG"')
    }
}
