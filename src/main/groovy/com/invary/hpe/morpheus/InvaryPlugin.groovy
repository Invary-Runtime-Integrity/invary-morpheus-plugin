// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import com.morpheusdata.core.Plugin
import com.morpheusdata.model.OptionType

class InvaryPlugin extends Plugin {

    @Override
    String getCode() {
        return 'invary'
    }

    @Override
    void initialize() {
        this.setName("Invary Integrity Measurement")
        this.setDescription("Invary Runtime Integrity integration")
        this.setAuthor("Invary")
        this.setSourceCodeLocationUrl("https://www.invary.com")
        this.setIssueTrackerUrl("https://www.invary.com")

        // Supplies the choice lists for task options -- must be
        // registered before the providers whose option types reference it.
        this.registerProvider(new InvaryOptionSourceProvider(this, morpheus))

        // Task to assist in installation of the Invary sensor on Morpheus managed
        // servers and instances
        this.registerProvider(new InvarySensorInstallTaskProvider(this, morpheus))
        
        // Integrity tab shown on Instance and Server details pages
        this.registerProvider(new InvaryInstanceTabProvider(this, morpheus))
        this.registerProvider(new InvaryServerTabProvider(this, morpheus))
        
        // Custom report (Invary Runtime Integrity) that reports the integrity of the
        // fleet of servers and instances that have the Invary sensor installed (shows
        // more detail than the instantaneous Analytics view).
        this.registerProvider(new InvaryCustomReportProvider(this, morpheus))

        // Analytics view (Invary / Runtime Integrity) that shows the integrity
        // of the fleet of servers and instances that have the Invary sensor installed.
        this.registerProvider(new InvaryAnalyticsProvider(this, morpheus))
    }

    @Override
    List<OptionType> getSettings() {
        return [
            new OptionType(
                name: 'API URL',
                code: 'invary-appraiser-url',
                inputType: OptionType.InputType.TEXT,
                fieldName: InvarySensorConfig.SETTING_API_URL,
                fieldLabel: 'API URL',
                fieldContext: 'config',
                helpText: 'The HTTPS API URL of the Invary Appraiser, normally on port 8443',
                displayOrder: 0,
                required: true
            ),
            new OptionType(
                name: 'API Token',
                code: 'invary-appraiser-api-token',
                inputType: OptionType.InputType.PASSWORD,
                fieldName: InvarySensorConfig.SETTING_API_TOKEN,
                fieldLabel: 'API Token',
                fieldContext: 'config',
                helpText: "Appraiser API auth token ('hpe.morpheus.appraiser.token')",
                displayOrder: 1,
                required: false
            ),
            new OptionType(
                name: 'Appraiser Ignore Cert Errors',
                code: 'invary-ignore-appraiser-cert-errors',
                inputType: OptionType.InputType.CHECKBOX,
                fieldName: InvarySensorConfig.SETTING_IGNORE_APPRAISER_CERT_ERRORS,
                fieldLabel: 'Appraiser Ignore Cert Errors',
                fieldContext: 'config',
                defaultValue: 'off',
                helpText: "Skip verification of the Appraiser's HTTPS certificate. Not recommended. " +
                          "Instead, import a self-signed or private-CA Appraiser certificate into the " +
                          "appliance's trusted certificates (see 'invary-appraiser morpheus setup')",
                displayOrder: 2,
                required: false
            ),
            new OptionType(
                name: 'Sensor Allow Untrusted Certs',
                code: 'invary-default-sensor-allow-untrusted-certs',
                inputType: OptionType.InputType.SELECT,
                optionSource: InvaryOptionSourceProvider.SOURCE_CERT_TRUST_MODES,
                optionSourceType: 'plugin',
                fieldName: InvarySensorConfig.SETTING_SENSOR_ALLOW_UNTRUSTED,
                fieldLabel: 'Sensor Allow Untrusted Certs',
                fieldContext: 'config',
                defaultValue: InvarySensorConfig.TRUST_AUTO,
                helpText: 'Whether installed sensors accept an untrusted appraiser certificate. ' +
                          "Does not affect this plugin's own connection to the Appraiser.",
                displayOrder: 3,
                required: false
            ),
            new OptionType(
                name: 'Remediation',
                code: 'invary-default-remediation-action',
                inputType: OptionType.InputType.SELECT,
                optionSource: InvaryOptionSourceProvider.SOURCE_REMEDIATION_ACTIONS,
                optionSourceType: 'plugin',
                fieldName: InvarySensorConfig.SETTING_REMEDIATION,
                fieldLabel: 'Remediation',
                fieldContext: 'config',
                defaultValue: InvarySensorConfig.REMEDIATION_NONE,
                helpText: 'Action to take on machines lacking integrity',
                displayOrder: 4,
                required: false
            ),
            new OptionType(
                name: 'Customer Package URL',
                code: 'invary-customer-package-url',
                inputType: OptionType.InputType.TEXT,
                fieldName: InvarySensorConfig.SETTING_CUSTOMER_PACKAGE_URL,
                fieldLabel: 'Customer Package URL',
                fieldContext: 'config',
                helpText: 'HTTPS URL of your Invary Customer Package (.tar.gz). ' +
                          ' May be re-hosted internally.',
                displayOrder: 5,
                required: true
            ),
        ]
    }

    /**
     * Called when a plugin is being removed from the plugin manager (aka Uninstalled)
     */
    @Override
    void onDestroy() {
        InvaryHttpClients.shutdown()
        InvaryCustomerPackage.clearCache()
        InvaryAppraiserAvailability.clear()
    }
}
