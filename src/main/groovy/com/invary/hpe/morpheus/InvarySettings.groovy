// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

/**
 * Reads the plugin settings configured at Administration -> Integrations -> Plugins ->
 * Invary Integrity Measurement.
 *
 * Morpheus stores the settings as a JSON document keyed by the field name of each option
 * type declared in {@link InvaryPlugin#getSettings()}.
 */
@Slf4j
class InvarySettings {

    /**
     * @return the plugin settings, or an empty map when none have been saved yet
     */
    static Map load(MorpheusContext morpheus, Plugin plugin) {
        try {
            String json = morpheus.getSettings(plugin).blockingGet()
            if (!json?.trim()) {
                return [:]
            }

            return (new JsonSlurper().parseText(json) ?: [:]) as Map
        } catch (Exception e) {
            log.error("Error fetching Invary plugin settings: ${e.message}", e)
            return [:]
        }
    }

    /**
     * Shown wherever appraiser results are presented while the plugin is not verifying the
     * appraiser's certificate.
     */
    static final String IGNORE_CERT_ERRORS_WARNING =
        'Appraiser certificate verification is disabled (Appraiser Ignore Cert Errors). Integrity ' +
        'results and the API token are not protected against interception.'

    /** The values Morpheus may store for a checked checkbox. */
    private static final Set<String> CHECKED = ['on', 'true', 'yes', '1'] as Set

    /**
     * Builds a client for the configured appraiser API.
     *
     * @return the client, or null when no API URL has been configured
     */
    static InvaryAppraiserClient appraiserClient(MorpheusContext morpheus, Plugin plugin) {
        return appraiserClient(morpheus, load(morpheus, plugin))
    }

    /**
     * Builds a client for a page view: it waits briefly, and fails at once while the appraiser
     * is known to be unreachable.
     *
     * @return the client, or null when no API URL has been configured
     */
    static InvaryAppraiserClient interactiveClient(MorpheusContext morpheus, Plugin plugin) {
        return appraiserClient(morpheus, load(morpheus, plugin), InvaryAppraiserClient.INTERACTIVE)
    }

    /**
     * Builds a client for the appraiser API named by already loaded settings.
     *
     * @return the client, or null when no API URL has been configured
     */
    static InvaryAppraiserClient appraiserClient(MorpheusContext morpheus, Map settings,
                                                 InvaryAppraiserClient.Budget budget = InvaryAppraiserClient.BACKGROUND) {
        String url = settings?.get(InvarySensorConfig.SETTING_API_URL)?.toString()?.trim()
        if (!url) {
            return null
        }

        String token = settings?.get(InvarySensorConfig.SETTING_API_TOKEN)?.toString()?.trim()
        boolean ignoreCertErrors = ignoreAppraiserCertErrors(settings)
        return new InvaryAppraiserClient(url, token, InvaryHttpClients.get(morpheus, ignoreCertErrors),
                                         ignoreCertErrors, budget)
    }

    /** Whether the plugin skips verification of the appraiser's certificate. */
    static boolean ignoreAppraiserCertErrors(Map settings) {
        return checked(settings?.get(InvarySensorConfig.SETTING_IGNORE_APPRAISER_CERT_ERRORS))
    }

    /**
     * @return the warning to show alongside appraiser results, or null when the appraiser's
     *         certificate is verified
     */
    static String appraiserWarning(Map settings) {
        return ignoreAppraiserCertErrors(settings) ? IGNORE_CERT_ERRORS_WARNING : null
    }

    /**
     * Reads a checkbox setting. Morpheus has stored a checked box as 'on', 'true' and true at
     * different times, so each of them counts as checked and anything else does not.
     */
    static boolean checked(Object value) {
        if (value instanceof Boolean) {
            return value
        }

        return value != null && CHECKED.contains(value.toString().trim().toLowerCase())
    }
}
