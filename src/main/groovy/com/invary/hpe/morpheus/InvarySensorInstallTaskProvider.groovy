// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import com.morpheusdata.core.ExecutableTaskInterface
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.TaskProvider
import com.morpheusdata.model.Container
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Icon
import com.morpheusdata.model.Instance
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.Task
import com.morpheusdata.model.TaskResult
import com.morpheusdata.model.TaskType
import com.morpheusdata.model.Workload
import groovy.util.logging.Slf4j

/**
 * Installs the Invary Sensor onto a managed server or instance.
 *
 * The provider both describes the task type and executes it. Morpheus can dispatch either
 * through the provider or through the service returned by getService(), which is deprecated,
 * so this class implements both interfaces and getService() returns itself. Every entry point
 * funnels into a single install routine.
 */
@Slf4j
class InvarySensorInstallTaskProvider implements TaskProvider, ExecutableTaskInterface {

    protected MorpheusContext morpheusContext
    protected Plugin plugin

    InvarySensorInstallTaskProvider(Plugin plugin, MorpheusContext morpheusContext) {
        this.morpheusContext = morpheusContext
        this.plugin = plugin
    }

    // -- Metadata -------------------------------------------------------------

    @Override
    MorpheusContext getMorpheus() {
        return morpheusContext
    }

    @Override
    Plugin getPlugin() {
        return plugin
    }

    @Override
    String getCode() {
        return 'invary-sensor-install-task'
    }

    @Override
    String getName() {
        return 'Invary Sensor - Install'
    }

    @Override
    String getDescription() {
        return 'Install the Invary Sensor'
    }

    @Override
    TaskType.TaskScope getScope() {
        return TaskType.TaskScope.all
    }

    @Override
    Boolean isAllowExecuteLocal() {
        return true
    }

    @Override
    Boolean isAllowExecuteRemote() {
        return true
    }

    @Override
    Boolean isAllowExecuteResource() {
        return true
    }

    @Override
    Boolean isAllowLocalRepo() {
        return false
    }

    @Override
    Boolean isAllowRemoteKeyAuth() {
        return true
    }

    @Override
    Boolean hasResults() {
        return true
    }

    @Override
    Icon getIcon() {
        // the asset pipeline strips the images directory, so assets are named by file alone.
        // the mark reads on both themes, so the light and dark icons are the same
        return new Icon(path: 'invary.svg', darkPath: 'invary.svg')
    }

    /**
     * Execution is implemented on this provider, so the deprecated service is this object.
     */
    @Override
    ExecutableTaskInterface getService() {
        return this
    }

    @Override
    List<OptionType> getOptionTypes() {
        return [
            new OptionType(
                name: 'Tags',
                code: InvarySensorConfig.OPTION_TAGS,
                inputType: OptionType.InputType.TEXT,
                fieldName: 'tags',
                fieldLabel: 'Tags',
                fieldContext: 'config',
                helpText: 'Additional comma separated tags to install the sensor with. The tag ' +
                          'identifying the Morpheus instance is always added automatically.',
                displayOrder: 0,
                required: false
            ),
            new OptionType(
                name: 'Remediation Action',
                code: InvarySensorConfig.OPTION_REMEDIATION,
                inputType: OptionType.InputType.SELECT,
                optionSource: InvaryOptionSourceProvider.SOURCE_REMEDIATION_ACTIONS_INHERIT,
                optionSourceType: 'plugin',
                fieldName: 'remediationAction',
                fieldLabel: 'Remediation Action',
                fieldContext: 'config',
                defaultValue: InvarySensorConfig.INHERIT,
                helpText: 'What the appraiser does to this instance when its integrity appraisal fails.',
                displayOrder: 1,
                required: false
            ),
            new OptionType(
                name: 'Measurement Interval',
                code: InvarySensorConfig.OPTION_INTERVAL,
                inputType: OptionType.InputType.TEXT,
                fieldName: 'measurementInterval',
                fieldLabel: 'Measurement Interval (seconds)',
                fieldContext: 'config',
                defaultValue: InvarySensorConfig.INHERIT,
                helpText: "How long the Invary Sensor waits between measurements, in seconds. " +
                          "Enter ${InvarySensorConfig.INHERIT} to keep whatever interval the machine " +
                          "already has, or ${InvarySensorConfig.INTERVAL_CLEAR} to clear it so the " +
                          "sensor measures at its own interval.",
                displayOrder: 2,
                required: false
            ),
            new OptionType(
                name: 'Allow Untrusted Certs',
                code: InvarySensorConfig.OPTION_ALLOW_UNTRUSTED,
                inputType: OptionType.InputType.SELECT,
                optionSource: InvaryOptionSourceProvider.SOURCE_CERT_TRUST_MODES_INHERIT,
                optionSourceType: 'plugin',
                fieldName: 'allowUntrustedCerts',
                fieldLabel: 'Allow Untrusted Certificates',
                fieldContext: 'config',
                defaultValue: InvarySensorConfig.INHERIT,
                helpText: 'Whether the installed sensor accepts an untrusted appraiser certificate.',
                displayOrder: 3,
                required: false
            ),
            new OptionType(
                name: 'Customer Package URL',
                code: InvarySensorConfig.OPTION_CUSTOMER_PACKAGE_URL,
                inputType: OptionType.InputType.TEXT,
                fieldName: 'customerPackageUrl',
                fieldLabel: 'Customer Package URL',
                fieldContext: 'config',
                defaultValue: InvarySensorConfig.INHERIT,
                helpText: "URL of the Invary customer package (.tar.gz) the package repository " +
                          "configuration is read from. Enter ${InvarySensorConfig.INHERIT} to use " +
                          "the URL configured in the plugin settings.",
                displayOrder: 4,
                required: false
            ),
        ]
    }

    // -- Execution ------------------------------------------------------------

    @Override
    TaskResult executeLocalTask(Task task, Map opts, Workload workload, ComputeServer server, Instance instance) {
        return runInstall(task, server, instance)
    }

    @Override
    TaskResult executeLocalTask(Task task, Map opts, Container container, ComputeServer server, Instance instance) {
        return runInstall(task, server, instance)
    }

    @Override
    TaskResult executeRemoteTask(Task task, Map opts, Workload workload, ComputeServer server, Instance instance) {
        return runInstall(task, server, instance)
    }

    @Override
    TaskResult executeRemoteTask(Task task, Workload workload, ComputeServer server, Instance instance) {
        return runInstall(task, server, instance)
    }

    @Override
    TaskResult executeRemoteTask(Task task, Map opts, Container container, ComputeServer server, Instance instance) {
        return runInstall(task, server, instance)
    }

    @Override
    TaskResult executeRemoteTask(Task task, Container container, ComputeServer server, Instance instance) {
        return runInstall(task, server, instance)
    }

    @Override
    TaskResult executeServerTask(ComputeServer server, Task task, Map opts) {
        return runInstall(task, server, null)
    }

    @Override
    TaskResult executeServerTask(ComputeServer server, Task task) {
        return runInstall(task, server, null)
    }

    @Override
    TaskResult executeContainerTask(Workload workload, Task task, Map opts) {
        return runInstall(task, workload?.server, workload?.instance)
    }

    @Override
    TaskResult executeContainerTask(Workload workload, Task task) {
        return runInstall(task, workload?.server, workload?.instance)
    }

    @Override
    TaskResult executeContainerTask(Container container, Task task, Map opts) {
        return runInstall(task, container?.server, container?.instance)
    }

    @Override
    TaskResult executeContainerTask(Container container, Task task) {
        return runInstall(task, container?.server, container?.instance)
    }

    private TaskResult runInstall(Task task, ComputeServer server, Instance instance) {
        try {
            if (!server) {
                throw new IllegalArgumentException('No target was supplied to install the Invary Sensor on.')
            }

            InvarySensorConfig config = resolveConfig(task, server, instance)
            log.info("Resolved Invary Sensor configuration: ${config}")

            InvarySensorInstaller.Result result = new InvarySensorInstaller(morpheus).install(server, config)

            return new TaskResult(
                success: result.success,
                exitCode: result.exitCode,
                msg: result.message,
                output: result.output,
                error: result.success ? '' : result.message
            )
        } catch (IllegalArgumentException e) {
            log.warn("Invary Sensor install is not configured correctly: ${e.message}")
            return failure(e.message)
        } catch (Exception e) {
            log.error("Invary Sensor install failed: ${e.message}", e)
            return failure("Invary Sensor install failed: ${e.message}")
        }
    }

    /**
     * Resolves the plugin settings and task options into the configuration an installation
     * needs, discovering anything left to the appraiser.
     */
    private InvarySensorConfig resolveConfig(Task task, ComputeServer server, Instance instance) {
        Map settings = InvarySettings.load(morpheus, plugin)
        Map options = optionValues(task)
        Long instanceId = resolveInstanceId(server, instance)
        if (!instanceId) {
            log.info("Server ${server?.id} belongs to no Morpheus instance; the sensor is tagged with the server alone")
        }

        InvaryAppraiserClient client = InvarySettings.appraiserClient(settings)
        Closure<Map> discovery = {
            if (!client) {
                return null
            }

            try {
                return client.fetchConfig()
            } catch (Exception e) {
                log.warn("Could not read the Invary Appraiser configuration: ${e.message}")
                return null
            }
        }

        return InvarySensorConfig.resolve(settings, options, server?.id, instanceId, discovery)
    }

    /**
     * Collects the saved task option values, keyed by option type code. The code is the
     * stable identifier for an option, and every value arrives as a string.
     */
    private static Map optionValues(Task task) {
        Map values = [:]
        task?.taskOptions?.each { option ->
            String code = option?.optionType?.code
            if (code) {
                values[code] = option.value
            }
        }

        return values
    }

    /**
     * Determines the Morpheus instance the server being installed on belongs to. Some execution
     * paths supply no instance, so it is looked up from the server in that case, and a server
     * that belongs to none is installed all the same: the appraiser identifies the machine by
     * its server, and the instance only records the service it is part of.
     */
    private Long resolveInstanceId(ComputeServer server, Instance instance) {
        if (instance?.id) {
            return instance.id
        }

        if (server?.id) {
            try {
                Workload workload = morpheus.async.workload
                    .find(new DataQuery().withFilter('server.id', server.id))
                    .blockingGet()

                if (workload?.instance?.id) {
                    return workload.instance.id
                }
            } catch (Exception e) {
                log.debug("Could not resolve an instance for server ${server.id}: ${e.message}")
            }
        }

        return null
    }

    private static TaskResult failure(String message) {
        return new TaskResult(
            success: false,
            exitCode: '1',
            msg: message,
            output: message,
            error: message
        )
    }
}
