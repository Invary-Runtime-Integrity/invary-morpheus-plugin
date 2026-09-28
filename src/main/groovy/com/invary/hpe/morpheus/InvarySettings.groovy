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
     * Builds a client for the configured appraiser API.
     *
     * @return the client, or null when no API URL has been configured
     */
    static InvaryAppraiserClient appraiserClient(MorpheusContext morpheus, Plugin plugin) {
        return appraiserClient(load(morpheus, plugin))
    }

    /**
     * Builds a client for the appraiser API named by already loaded settings.
     *
     * @return the client, or null when no API URL has been configured
     */
    static InvaryAppraiserClient appraiserClient(Map settings) {
        String url = settings?.get(InvarySensorConfig.SETTING_API_URL)?.toString()?.trim()
        String token = settings?.get(InvarySensorConfig.SETTING_API_TOKEN)?.toString()?.trim()
        return url ? new InvaryAppraiserClient(url, token) : null
    }
}
