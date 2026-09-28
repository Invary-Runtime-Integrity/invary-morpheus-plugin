// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.TaskResult
import groovy.util.logging.Slf4j

/**
 * Installs and configures the Invary Sensor on a managed server.
 *
 * A run is two commands: a probe that reports the architecture, whether a sensor is already
 * present and the current configuration, then a single script that performs the install.
 */
@Slf4j
class InvarySensorInstaller {

    static final String TARGET_LINUX = 'linux'

    /** The only architecture Invary publishes Linux packages for. */
    static final String PACKAGE_ARCH = 'x86_64'

    /** How much of a transcript stands in for a summary a run did not produce. */
    private static final int TAIL_LINES = 20

    /** What an installation produced, for reporting back through the task result. */
    static class Result {
        boolean success
        String exitCode
        String message
        String output
    }

    private final MorpheusContext morpheus

    InvarySensorInstaller(MorpheusContext morpheus) {
        this.morpheus = morpheus
    }

    /**
     * Installs the sensor described by a resolved configuration.
     *
     * @throws IllegalArgumentException if the node is not one the sensor can be installed on
     */
    Result install(ComputeServer server, InvarySensorConfig config) {
        String target = resolveTarget(server)
        String name = describe(server)
        log.info("Installing the Invary Sensor on ${name} (${target})")

        TaskResult probed = execute(server, InvarySensorScripts.linuxProbe())

        if (!succeeded(probed)) {
            return failed('The target could not be inspected before installing.', probed)
        }

        InvarySensorScripts.Probe probe = InvarySensorScripts.parseProbe(reported(probed))
        log.info("Target reports ${probe}")

        String script = buildScript(name, config, probe)
        log.debug("Running on ${name}:\n${script}")

        TaskResult installed = execute(server, script)
        if (!succeeded(installed)) {
            return failed('The Invary Sensor could not be installed.', installed)
        }

        String action = probe.installed ? 'reconfigured' : 'installed'
        String converted = probe.shellInstall
            ? ', converted from the shell installer to the system package'
            : ''

        // a repository swap decides where every later upgrade of this machine comes from, so it
        // belongs in the message the history lists rather than only in the transcript
        String transcript = reported(installed)
        String channel = InvarySensorScripts.parseChannelChange(transcript)
        if (channel) {
            log.info("${name} moved package channel ${channel}")
        }

        return new Result(
            success: true,
            exitCode: installed.exitCode ?: '0',
            message: "Invary Sensor ${action} on ${name}${converted}" +
                     (channel ? ", package channel ${channel}" : ''),
            output: transcript,
        )
    }

    /**
     * Builds the script for the run, having first refused the combinations that cannot work.
     *
     * Every refusal happens here, after the probe but before anything is stopped or backed up,
     * so a node that cannot be installed upon is left exactly as it was found.
     *
     * @throws IllegalArgumentException if the node cannot be installed upon as configured
     */
    private String buildScript(String name, InvarySensorConfig config,
                               InvarySensorScripts.Probe probe) {
        // the sensor always comes from the Invary package repository
        String merged = InvarySensorScripts.mergeToml(probe.config, config)

        checkRepositoryTarget(name, probe)

        // fetched here rather than before the probe, so a node that cannot be installed on is
        // refused without anything being downloaded for it. The appliance holds the archive, so
        // only the first server of a fleet pays for the transfer
        byte[] repoPackage = InvaryCustomerPackage.repoPackage(config.customerPackageUrl, probe.pkgFmt)
        log.info("Read the ${probe.pkgFmt} repo package (${repoPackage.length} bytes) from the " +
                 "customer package at ${config.customerPackageUrl}")

        return InvarySensorScripts.linuxPackageScript(config, probe, merged, repoPackage)
    }

    /**
     * Refuses a node the package repository cannot serve.
     *
     * @throws IllegalArgumentException naming what about the node or its configuration is in
     *         the way, and what to do about it
     */
    private void checkRepositoryTarget(String name, InvarySensorScripts.Probe probe) {
        // no aarch64 packages are published, and there is no fallback: a machine that cannot be
        // served is left with whatever sensor it already had
        if (probe.arch != PACKAGE_ARCH) {
            throw new IllegalArgumentException(
                "The Invary Sensor cannot be installed on '${name}': Invary packages are not published " +
                "for ${probe.arch}. Only ${PACKAGE_ARCH} Linux targets are supported.")
        }

        if (!probe.pkgFmt || !probe.pkgTool) {
            throw new IllegalArgumentException(
                "The Invary Sensor cannot be installed on '${name}': no supported package manager was found. " +
                'The Invary package repository needs dnf, yum or apt-get.')
        }

        // both are replaced by the repository the customer package configures, which the script
        // does once it has read which channel that package is for. Neither refuses the node:
        // supplying a customer package is the statement of which channel this fleet is on
        if (probe.foreignRepos) {
            log.warn("${name} carries Invary repository files that no package owns, which this install " +
                     "removes: ${probe.foreignRepos.join(', ')}")
        }

        if (probe.repoPackages) {
            log.info("${name} is configured for an Invary channel by ${probe.repoPackages.join(', ')}, " +
                     'which this install replaces if the customer package names another')
        }
    }

    /**
     * Collects everything a command reported back.
     *
     * Which field carries a command's output depends on how Morpheus reached the target - a
     * hypervisor guest exec reports process state where an SSH session reports plain output -
     * so every field that could hold it is considered.
     */
    private static String reported(TaskResult result) {
        return [result?.output, result?.data, result?.msg, result?.error]
            .collect { it?.toString() }
            .findAll { it?.trim() }
            .unique()
            .join('\n')
    }

    /** Names a server for a log line, since not every server carries a name. */
    private static String describe(ComputeServer server) {
        return [server?.name, server?.hostname, server?.externalIp, server?.internalIp]
            .find { it?.trim() } ?: "server ${server?.id}"
    }

    /**
     * Establishes that a node is one the sensor can be installed on, which is a Linux node.
     * Morpheus reports the platform but not the processor architecture, which is why the probe
     * still has to ask the node itself.
     *
     * Every field that describes the operating system is considered, since none of them is
     * always the one carrying the platform. The `platform` of a server holds the distribution
     * rather than the platform - an Ubuntu node reports `ubuntu` there and a Rocky node `rocky`
     * - and the operating system type, which does hold it, is a relation that is not carried on
     * every server a task is handed.
     *
     * Windows is refused wherever it is reported, and Linux is accepted only once nothing has
     * reported Windows: `osType` is a column that stands at `linux` until Morpheus sets it, so
     * it says Linux about a server it knows nothing about, including a Windows one.
     *
     * @throws IllegalArgumentException naming the node and why it cannot be installed on, which
     *         is what the task reports as its failure
     */
    static String resolveTarget(ComputeServer server) {
        List<String> reported = [
            server?.serverOs?.platform?.toString(),
            server?.osType,
            server?.platform,
        ].collect { it?.trim()?.toLowerCase() }.findAll { it }

        if (!reported) {
            throw new IllegalArgumentException(
                "The operating system of '${server?.name}' could not be determined, so the Invary Sensor could not be installed.")
        }

        if (reported.any { it.contains('windows') }) {
            throw new IllegalArgumentException(
                "The Invary Sensor cannot be installed on '${server?.name}', which runs Windows. " +
                'The Invary Sensor install task supports Linux servers only.')
        }

        if (reported.any { it.contains('linux') }) {
            return TARGET_LINUX
        }

        throw new IllegalArgumentException(
            "The Invary Sensor cannot be installed on '${server?.name}', which runs ${reported.unique().join(', ')}. " +
            'The Invary Sensor install task supports Linux servers only.')
    }

    /**
     * Runs a command on the node. The credential arguments are left null so that the
     * connection details configured on the server are used, and the command is run with
     * elevated privileges, which installing a system service requires.
     */
    private TaskResult execute(ComputeServer server, String command) {
        return morpheus.executeCommandOnServer(
            server,      // target
            command,     // command
            null,        // rpc
            null,        // sshUsername
            null,        // sshPassword
            null,        // publicKey
            null,        // privateKey
            null,        // passPhrase
            true,        // noProfile
            true         // sudo
        ).blockingGet()
    }

    private static boolean succeeded(TaskResult result) {
        return result?.success
    }

    /**
     * Reports a failed run.
     *
     * The transcript is the whole account of what happened on the node and nothing else is kept
     * once the run is over, so it is logged in full and carried back as the output. The message
     * is not the transcript, though: Morpheus elides the middle of a long message, which is
     * where any diagnostics land, so the message carries only the short summary of the cause
     * that the run ended with.
     */
    private static Result failed(String message, TaskResult result) {
        String transcript = reported(result)
        InvarySensorScripts.Failure failure = InvarySensorScripts.parseFailure(transcript)
        String exitCode = failure?.exitCode ?: result?.exitCode?.toString() ?: '1'

        String headline = failure?.step
            ? "${message} It failed while ${failure.step} (exit ${exitCode})."
            : "${message} (exit ${exitCode})"

        log.error("${headline}\n${transcript?.trim() ?: '(the target reported nothing)'}")

        // a run that failed before it could summarize itself, or one whose transcript did not
        // survive the transport, still has to report what it can
        String cause = InvarySensorScripts.parseSummary(transcript) ?: tail(transcript)

        return new Result(
            success: false,
            exitCode: exitCode,
            message: cause ? "${headline}\n${cause}" : headline,
            output: transcript,
        )
    }

    /**
     * The end of a transcript, which is where a failure is, kept short enough to be reported
     * whole. It stands in for a summary the run did not produce.
     */
    private static String tail(String text) {
        String trimmed = text?.trim()
        if (!trimmed) {
            return null
        }

        List<String> lines = trimmed.readLines()
        return lines.size() > TAIL_LINES ? lines.takeRight(TAIL_LINES).join('\n') : trimmed
    }
}
