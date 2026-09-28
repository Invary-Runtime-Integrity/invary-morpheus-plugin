// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.apache.http.client.methods.HttpGet
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.ssl.SSLContextBuilder
import org.apache.http.util.EntityUtils

/**
 * Reads from the Invary Appraiser HTTPs API.
 *
 * Currently every request trusts any server certificate -- customers
 * often deploy the appraiser with self-signed certs and it is difficult to create
 * a Morpheus plugin that can store a custom cert to be used for validation.
 *
 * Appraiser morpheus API endpoints are protected with an API token that is generated
 * by `invary-appraiser morpheus setup` and must be set on the plugin configuration
 * screen.
 */
@Slf4j
class InvaryAppraiserClient {

    private final String baseUrl
    private final String apiToken

    /**
     * @param baseUrl the appraiser API base URL, normally on port 8443
     * @param apiToken the token this plugin presents to the appraiser, which must match the
     *                 appraiser's hpe.morpheus.appraiser.token.
     */
    InvaryAppraiserClient(String baseUrl, String apiToken = null) {
        this.baseUrl = baseUrl?.trim()?.replaceAll('/+$', '')
        this.apiToken = apiToken?.trim() ?: null
    }

    String getBaseUrl() {
        return baseUrl
    }

    /**
     * Retrieves a Morpheus server's latest appraisal (by Morpheus server id).
     *
     * @return the appraisal, or null when the appraiser has none for the server
     */
    Map fetchServerAppraisal(Long serverId) {
        return getJson("/api/morpheus/server/${serverId}") as Map
    }

    /**
     * Retrieves a Morpheus server's latest appraisal (by Invary endpoint id).
     * This reaches a machine the appraiser has appraised but which carries no server tag.
     *
     * @return the appraisal, or null when the appraiser has none for the endpoint
     */
    Map fetchEndpointAppraisal(String endpointId) {
        return getJson("/api/morpheus/endpoint/${endpointId}") as Map
    }

    /**
     * Retrieves the integrity of a Morpheus instance, rolled up over the most recent appraisals
     * of servers that make it up. An instance is a service rather than a machine, so it is
     * never appraised itself.
     *
     * @return the roll up, or null when no server of the instance has been appraised
     */
    Map fetchInstanceReport(Long instanceId) {
        return getJson("/api/morpheus/instance/${instanceId}") as Map
    }

    /**
     * Retrieves a summary of the last appraisal of every machine the appraiser knows about,
     * reporting fleet counts alongside one entry per machine.
     *
     * @return the fleet report, or null when the appraiser does not publish one
     */
    Map fetchFleetReport() {
        return getJson('/api/morpheus/report') as Map
    }

    /**
     * Retrieves the settings a node needs in order to point a sensor at this appraiser,
     * reporting the sensor endpoint URL and whether its certificate is self signed.
     *
     * @return the configuration, or null when this appraiser does not publish one
     */
    Map fetchConfig() {
        return getJson('/api/morpheus/config') as Map
    }

    /**
     * @return the parsed response body, or null when the appraiser reports it has no such
     *         resource
     */
    private Object getJson(String path) {
        String url = "${baseUrl}${path}"
        log.info("Fetching from Invary Appraiser: ${url}")

        CloseableHttpClient httpClient = createHttpClient()
        try {
            def request = new HttpGet(url)
            request.setHeader('Accept', 'application/json')
            if (apiToken) {
                request.setHeader('Authorization', "Bearer ${apiToken}")
            }

            def response = httpClient.execute(request)
            try {
                int statusCode = response.statusLine.statusCode
                String body = EntityUtils.toString(response.entity)

                if (statusCode == 200) {
                    return new JsonSlurper().parseText(body)
                }

                if (statusCode == 404) {
                    log.info("Invary Appraiser has no resource at ${path} (HTTP 404)")
                    return null
                }

                // the most likely cause is a mismatched token
                if (statusCode == 401) {
                    String reason = apiToken
                        ? 'The Appraiser rejected this plugin\'s Appraiser API Token. Check that it ' +
                          'matches hpe.morpheus.appraiser.token in the Appraiser configuration exactly.'
                        : 'The Appraiser requires an API token and this plugin has none. Set Appraiser ' +
                          'API Token in the plugin settings to the Appraiser\'s hpe.morpheus.appraiser.token.'

                    log.warn("Invary Appraiser refused ${path}: ${reason}")
                    throw new RuntimeException(reason)
                }

                log.warn("Invary Appraiser returned HTTP ${statusCode} for ${path}: ${body}")
                throw new RuntimeException("Invary Appraiser returned HTTP ${statusCode}")
            } finally {
                response.close()
            }
        } finally {
            httpClient.close()
        }
    }

    private static CloseableHttpClient createHttpClient() {
        def sslContext = new SSLContextBuilder()
            .loadTrustMaterial(null, { chain, authType -> true })
            .build()
        def sslSocketFactory = new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE)

        return HttpClients.custom().setSSLSocketFactory(sslSocketFactory).build()
    }
}
