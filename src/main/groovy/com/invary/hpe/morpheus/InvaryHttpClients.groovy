// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.NetworkProxy
import groovy.util.logging.Slf4j

/**
 * Holds the one pooled HTTP client the plugin reaches the appraiser with.
 *
 * HttpApiClient builds its underlying connection pool on its first request and keeps the
 * certificate verification and proxy that request asked for, so a client is only reused while
 * those stay the same. A change to either - the Appraiser Ignore Cert Errors setting, or the
 * appliance's global proxy - replaces the client and shuts down the old one.
 */
@Slf4j
class InvaryHttpClients {

    private static final Object LOCK = new Object()

    private static List current
    private static HttpApiClient client

    /**
     * @param ignoreCertErrors whether the client skips verification of the appraiser's certificate
     * @return the client for the given verification and the appliance's current proxy
     */
    static HttpApiClient get(MorpheusContext morpheus, boolean ignoreCertErrors) {
        NetworkProxy proxy = globalProxy(morpheus)
        List key = [ignoreCertErrors, proxy?.proxyHost, proxy?.proxyPort, proxy?.proxyUser, proxy?.proxyPassword,
                    proxy?.proxyDomain, proxy?.proxyWorkstation, proxy?.noProxy]

        synchronized (LOCK) {
            if (client == null || key != current) {
                // a request still running on the old client may fail as it is shut down, which
                // happens only when the settings it was built for have just changed
                client?.shutdownClient()

                if (ignoreCertErrors) {
                    log.warn("Invary Appraiser certificate verification is disabled by the 'Appraiser Ignore Cert Errors' plugin setting")
                }

                client = new HttpApiClient(true)
                client.networkProxy = proxy
                // the appraiser keeps no session, and cookies shared by every tenant's requests
                // would be worse than none
                client.useCookies = false
                current = key
            }

            return client
        }
    }

    /** Shuts down the client, as the plugin is removed. */
    static void shutdown() {
        synchronized (LOCK) {
            client?.shutdownClient()
            client = null
            current = null
        }
    }

    /** @return the appliance's global proxy, or null when there is none or it cannot be read */
    static NetworkProxy globalProxy(MorpheusContext morpheus) {
        if (morpheus == null) {
            return null
        }

        try {
            return morpheus.services.setting.globalNetworkProxy
        } catch (Exception e) {
            log.warn("Could not read the appliance's global network proxy, so the Invary Appraiser is reached directly: ${e.message}")
            return null
        }
    }
}
