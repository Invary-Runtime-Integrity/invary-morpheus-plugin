// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

/**
 * Reads from the Invary Appraiser HTTPs API.
 *
 * Requests go through Morpheus's HttpApiClient, which verifies the appraiser's certificate
 * against the appliance's Java trust store and checks that it names the host of the API URL. An
 * appraiser with a self-signed or private-CA certificate is verified once that certificate has
 * been imported into the appliance's trusted certificates; `invary-appraiser morpheus setup`
 * prints it along with the steps. The Appraiser Ignore Cert Errors plugin setting turns
 * verification off.
 *
 * Appraiser morpheus API endpoints are protected with an API token that is generated
 * by `invary-appraiser morpheus setup` and must be set on the plugin configuration
 * screen.
 */
@Slf4j
class InvaryAppraiserClient {

    /**
     * How long a request may take to connect and to be answered, and whether it fails at once
     * while the appraiser is known to be unreachable.
     */
    static class Budget {
        final int connectMillis
        final int readMillis
        final boolean interactive

        Budget(int connectMillis, int readMillis, boolean interactive) {
            this.connectMillis = connectMillis
            this.readMillis = readMillis
            this.interactive = interactive
        }
    }

    /**
     * For a page view, which holds a Morpheus request thread while it waits. The appraiser is on
     * the local network and answers from memory, so this is generous for one that is healthy.
     */
    static final Budget INTERACTIVE = new Budget(2_000, 5_000, true)

    /** For the install task and the report, which run in the background and always try. */
    static final Budget BACKGROUND = new Budget(10_000, 30_000, false)

    // an appraiser explains an error in a few hundred characters; anything past this is not
    // the appraiser's explanation
    private static final int MAX_LOGGED_BODY = 4096

    // how the JDK and Apache HttpClient describe a certificate that does not chain to a trusted
    // root, or has expired, and one that does not name the host it was reached by. HttpApiClient
    // catches the exception, so only its message is left to tell them apart
    private static final List<String> UNTRUSTED_MARKERS = [
        'PKIX path building failed',
        'PKIX path validation failed',
        'unable to find valid certification path',
    ].asImmutable()
    private static final List<String> HOSTNAME_MARKERS = [
        "doesn't match any of the subject alternative names",
        'No subject alternative',
    ].asImmutable()

    private final String baseUrl
    private final String apiToken
    private final HttpApiClient http
    private final boolean ignoreCertErrors
    private final Budget budget

    /**
     * @param baseUrl the appraiser API base URL, normally on port 8443
     * @param apiToken the token this plugin presents to the appraiser, which must match the
     *                 appraiser's hpe.morpheus.appraiser.token.
     * @param http the client requests are made with
     * @param ignoreCertErrors whether verification of the appraiser's certificate is skipped
     * @param budget the timeouts requests are made with
     */
    InvaryAppraiserClient(String baseUrl, String apiToken, HttpApiClient http, boolean ignoreCertErrors,
                          Budget budget = BACKGROUND) {
        this.baseUrl = baseUrl?.trim()?.replaceAll('/+$', '')
        this.apiToken = apiToken?.trim() ?: null
        this.http = http
        this.ignoreCertErrors = ignoreCertErrors
        this.budget = budget
    }

    Budget getBudget() {
        return budget
    }

    String getBaseUrl() {
        return baseUrl
    }

    /** Whether this client skips verification of the appraiser's certificate. */
    boolean isIgnoringCertErrors() {
        return ignoreCertErrors
    }

    /**
     * @return the warning to show alongside results read through this client, or null when the
     *         appraiser's certificate is verified
     */
    String getWarning() {
        return ignoreCertErrors ? InvarySettings.IGNORE_CERT_ERRORS_WARNING : null
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
        if (budget.interactive) {
            String unavailable = InvaryAppraiserAvailability.unavailable(baseUrl)
            if (unavailable) {
                log.debug("Not fetching ${path}: ${unavailable}")
                throw new RuntimeException(unavailable)
            }
        }

        log.debug("Fetching from Invary Appraiser: ${baseUrl}${path}")

        Map<CharSequence, CharSequence> headers = ['Accept': 'application/json']
        if (apiToken) {
            headers['Authorization'] = "Bearer ${apiToken}".toString()
        }

        // ignoreSSL defaults to true in HttpApiClient, so it is always set here
        def opts = new HttpApiClient.RequestOptions(
            headers: headers,
            ignoreSSL: ignoreCertErrors,
            timeout: budget.connectMillis,
            connectionTimeout: budget.connectMillis,
            readTimeout: budget.readMillis,
        )

        ServiceResponse response = http.callApi(baseUrl, path, null, null, opts, 'GET')
        return interpret(baseUrl, path, apiToken != null, response)
    }

    /**
     * Turns the response to a request into its parsed body, null for a resource the appraiser
     * does not have, or an exception that says what is wrong in terms an operator can act on.
     */
    static Object interpret(String baseUrl, String path, boolean hasToken, ServiceResponse response) {
        String status = response.success ? response.statusCode : response.errorCode
        String body = response.content

        // any status means the appraiser answered, whatever it answered
        if (status) {
            InvaryAppraiserAvailability.reached(baseUrl)
        }

        if (response.success) {
            return body?.trim() ? new JsonSlurper().parseText(body) : null
        }

        if (status == '404') {
            log.debug("Invary Appraiser has no resource at ${path} (HTTP 404)")
            return null
        }

        // the most likely cause is a mismatched token
        if (status == '401') {
            String reason = hasToken
                ? 'The Appraiser rejected this plugin\'s Appraiser API Token. Check that it ' +
                  'matches hpe.morpheus.appraiser.token in the Appraiser configuration exactly.'
                : 'The Appraiser requires an API token and this plugin has none. Set Appraiser ' +
                  'API Token in the plugin settings to the Appraiser\'s hpe.morpheus.appraiser.token.'

            log.warn("Invary Appraiser refused ${path}: ${reason}")
            throw new RuntimeException(reason)
        }

        if (status) {
            log.warn("Invary Appraiser returned HTTP ${status} for ${path}: ${InvaryText.loggable(body, MAX_LOGGED_BODY)}")
            throw new RuntimeException("Invary Appraiser returned HTTP ${status}")
        }

        // no status means the request never completed: HttpApiClient reports why only as text
        String detail = ([response.error, response.msg] + (response.errors?.values() ?: []))
            .findAll { it }
            .unique()
            .join('; ')

        // a certificate problem is answered at once, and an operator who has just fixed it should
        // see the result on the next view
        if (!certificateProblem(detail)) {
            InvaryAppraiserAvailability.failed(baseUrl, detail)
        }

        String reason = failureReason(baseUrl, detail)
        log.warn("Could not read ${path} from the Invary Appraiser: ${reason}")
        throw new RuntimeException(reason)
    }

    /** Whether what HttpApiClient reported is the appraiser's certificate being refused. */
    static boolean certificateProblem(String detail) {
        String text = detail ?: ''
        return (UNTRUSTED_MARKERS + HOSTNAME_MARKERS).any { text.contains(it) }
    }

    /**
     * Describes why a request to the appraiser failed before a response was received.
     *
     * @param detail what HttpApiClient reported about the failure
     */
    static String failureReason(String baseUrl, String detail) {
        String text = detail ?: ''

        if (UNTRUSTED_MARKERS.any { text.contains(it) }) {
            return 'The Appraiser\'s certificate is not trusted by this appliance. Import it into the ' +
                   'appliance\'s trusted certificates (run \'invary-appraiser morpheus setup\' on the ' +
                   'Appraiser for the certificate and the steps), or, not recommended, enable Appraiser ' +
                   'Ignore Cert Errors in the plugin settings.'
        }

        if (HOSTNAME_MARKERS.any { text.contains(it) }) {
            String host = hostOf(baseUrl)
            return "The Appraiser's certificate does not name ${host ?: 'the host in the API URL'}. Set the " +
                   'API URL to a name the certificate covers, or reissue the Appraiser certificate with ' +
                   "that name (run 'invary-appraiser morpheus setup --appraiser-url ${baseUrl}' on the " +
                   'Appraiser for the steps).'
        }

        return "Could not reach the Invary Appraiser at ${baseUrl}" + (text ? ": ${text}" : '.')
    }

    private static String hostOf(String url) {
        try {
            return url ? new URI(url).host : null
        } catch (URISyntaxException ignored) {
            return null
        }
    }
}
