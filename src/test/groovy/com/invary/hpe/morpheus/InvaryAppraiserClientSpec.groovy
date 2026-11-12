// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.response.ServiceResponse
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore

/**
 * Reading the appraiser through HttpApiClient, against a local HTTPS server with a self-signed
 * certificate of the kind `invary-appraiser configure` generates.
 *
 * The certificate is generated with the JDK's keytool when the spec starts rather than kept as a
 * fixture, so there is no committed key and nothing to expire.
 */
class InvaryAppraiserClientSpec extends Specification {

    static final char[] PASSWORD = 'changeit'.toCharArray()
    static final String TOKEN = 'appraiser-token'

    @Shared HttpsServer server
    @Shared String baseUrl
    @Shared Path dir
    @Shared List<String> authorizations = Collections.synchronizedList([])

    def setupSpec() {
        dir = Files.createTempDirectory('invary-appraiser-client')
        Path keystore = dir.resolve('appraiser.p12')
        keytool('-genkeypair', '-alias', 'appraiser', '-keyalg', 'EC', '-groupname', 'secp256r1',
                '-dname', 'CN=Server, O=Invary', '-ext', 'SAN=dns:localhost,ip:127.0.0.1', '-validity', '2',
                '-storetype', 'PKCS12', '-keystore', keystore.toString(),
                '-storepass', new String(PASSWORD), '-keypass', new String(PASSWORD))

        KeyStore ks = KeyStore.getInstance('PKCS12')
        keystore.withInputStream { ks.load(it, PASSWORD) }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.defaultAlgorithm)
        kmf.init(ks, PASSWORD)
        SSLContext context = SSLContext.getInstance('TLS')
        context.init(kmf.keyManagers, null, null)

        server = HttpsServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.httpsConfigurator = new HttpsConfigurator(context)
        server.createContext('/api/morpheus/report') { respond(it, 200, '{"endpoints":[{"name":"web-1"}]}') }
        server.createContext('/api/morpheus/server/1') { respond(it, 404, '') }
        server.createContext('/api/morpheus/config') { respond(it, 401, '') }
        server.createContext('/api/morpheus/instance/1') { respond(it, 500, 'broken') }
        server.start()

        baseUrl = "https://localhost:${server.address.port}"
    }

    def cleanupSpec() {
        server?.stop(0)
        InvaryHttpClients.shutdown()
        dir?.toFile()?.deleteDir()
    }

    def setup() {
        authorizations.clear()
        InvaryAppraiserAvailability.clear()
    }

    def cleanup() {
        InvaryAppraiserAvailability.clear()
    }

    /** Answers every request with what it is given, recording the options each was made with. */
    static class StubHttp extends HttpApiClient {
        List<HttpApiClient.RequestOptions> requests = Collections.synchronizedList([])
        ServiceResponse answer

        @Override
        ServiceResponse callApi(String url, String path, String username, String password,
                                HttpApiClient.RequestOptions opts, String method) {
            requests << opts
            return answer
        }
    }

    static ServiceResponse unreachable() {
        return new ServiceResponse(success: false, error: 'Connect to appraiser.example:8443 failed: Connect timed out')
    }

    static ServiceResponse answered() {
        return new ServiceResponse(success: true, statusCode: '200', content: '{"endpoints":[]}')
    }

    private void respond(HttpExchange exchange, int status, String body) {
        authorizations << exchange.requestHeaders.getFirst('Authorization')
        byte[] bytes = body.getBytes('UTF-8')
        exchange.responseHeaders.add('Content-Type', 'application/json')
        exchange.sendResponseHeaders(status, bytes.length ?: -1)
        if (bytes.length) {
            exchange.responseBody.write(bytes)
        }
        exchange.close()
    }

    private static void keytool(String... args) {
        String keytool = new File(System.getProperty('java.home'), 'bin/keytool').path
        Process process = ([keytool] + args.toList()).execute()
        StringBuilder output = new StringBuilder()
        process.waitForProcessOutput(output, output)
        if (process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: ${output}")
        }
    }

    private InvaryAppraiserClient client(boolean ignoreCertErrors, String token = TOKEN) {
        return new InvaryAppraiserClient(baseUrl, token, InvaryHttpClients.get(null, ignoreCertErrors), ignoreCertErrors)
    }

    // -- Certificate verification ---------------------------------------------

    def "an appraiser certificate the appliance does not trust is refused with a message saying what to do"() {
        when:
        client(false).fetchFleetReport()

        then:
        RuntimeException e = thrown()
        e.message.contains('not trusted by this appliance')
        e.message.contains('invary-appraiser morpheus setup')
        authorizations.isEmpty()

        and:
        // a certificate problem is not the appraiser being unreachable
        InvaryAppraiserAvailability.unavailable(baseUrl) == null
    }

    def "ignoring certificate errors reaches the appraiser and presents the token"() {
        when:
        Map report = client(true).fetchFleetReport()

        then:
        report.endpoints*.name == ['web-1']
        authorizations == ["Bearer ${TOKEN}".toString()]
    }

    def "only a client that ignores certificate errors carries the warning"() {
        expect:
        client(true).warning == InvarySettings.IGNORE_CERT_ERRORS_WARNING
        client(true).ignoringCertErrors
        client(false).warning == null
        !client(false).ignoringCertErrors
    }

    // -- Responses ------------------------------------------------------------

    def "a resource the appraiser does not have is null"() {
        expect:
        client(true).fetchServerAppraisal(1L) == null
    }

    def "a refused token is reported as a token problem"() {
        when:
        client(true).fetchConfig()

        then:
        RuntimeException e = thrown()
        e.message.contains("rejected this plugin's Appraiser API Token")
    }

    def "a missing token is reported as missing"() {
        when:
        client(true, null).fetchConfig()

        then:
        RuntimeException e = thrown()
        e.message.contains('this plugin has none')
    }

    def "any other status is reported with its code"() {
        when:
        client(true).fetchInstanceReport(1L)

        then:
        RuntimeException e = thrown()
        e.message == 'Invary Appraiser returned HTTP 500'
    }

    // -- Failure descriptions -------------------------------------------------

    @Unroll
    def "a failure reading '#detail' is described as #kind"() {
        expect:
        InvaryAppraiserClient.failureReason('https://appraiser.example:8443', detail).contains(expected)

        where:
        kind              | detail                                                                                                  || expected
        'untrusted'       | 'sun.security.validator.ValidatorException: PKIX path building failed: unable to find valid certification path' || 'not trusted by this appliance'
        'expired'         | 'PKIX path validation failed: java.security.cert.CertPathValidatorException: validity check failed'    || 'not trusted by this appliance'
        'a host mismatch' | "Certificate for <appraiser.example> doesn't match any of the subject alternative names: [localhost]"  || 'does not name appraiser.example'
        'a host mismatch' | 'No subject alternative DNS name matching appraiser.example found.'                                      || 'does not name appraiser.example'
        'unreachable'     | 'Connect to appraiser.example:8443 failed: Connection refused'                                          || 'Could not reach the Invary Appraiser at https://appraiser.example:8443: Connect to'
        'unreachable'     | null                                                                                                    || 'Could not reach the Invary Appraiser at https://appraiser.example:8443.'
    }

    def "a host mismatch points to the setup command for the steps to reissue the certificate"() {
        when:
        String reason = InvaryAppraiserClient.failureReason('https://appraiser.example:8443',
            'No subject alternative DNS name matching appraiser.example found.')

        then:
        reason.contains("'invary-appraiser morpheus setup --appraiser-url https://appraiser.example:8443'")
        !reason.contains('--certificate-san')
    }

    // -- Timeouts and the fail-fast window ------------------------------------

    static final String APPRAISER = 'https://appraiser.example:8443'

    def "a page view waits briefly and the background waits longer"() {
        given:
        def http = new StubHttp(answer: answered())

        when:
        new InvaryAppraiserClient(APPRAISER, TOKEN, http, false, InvaryAppraiserClient.INTERACTIVE).fetchFleetReport()
        new InvaryAppraiserClient(APPRAISER, TOKEN, http, false).fetchFleetReport()

        then:
        http.requests*.connectionTimeout == [2_000, 10_000]
        http.requests*.timeout == [2_000, 10_000]
        http.requests*.readTimeout == [5_000, 30_000]
    }

    def "the settings build a background client unless asked for an interactive one"() {
        given:
        Map settings = [(InvarySensorConfig.SETTING_API_URL): APPRAISER]

        expect:
        InvarySettings.appraiserClient(null, settings).budget.is(InvaryAppraiserClient.BACKGROUND)
        InvarySettings.appraiserClient(null, settings, InvaryAppraiserClient.INTERACTIVE).budget.is(InvaryAppraiserClient.INTERACTIVE)
    }

    def "an unreachable appraiser fails page views at once until the window passes"() {
        given:
        def http = new StubHttp(answer: unreachable())
        def page = new InvaryAppraiserClient(APPRAISER, TOKEN, http, false, InvaryAppraiserClient.INTERACTIVE)

        when:
        page.fetchFleetReport()

        then:
        RuntimeException first = thrown()
        first.message.startsWith("Could not reach the Invary Appraiser at ${APPRAISER}")

        when:
        page.fetchServerAppraisal(1L)

        then:
        RuntimeException second = thrown()
        second.message.startsWith("The Invary Appraiser at ${APPRAISER} could not be reached 0 s ago (Connect to")
        second.message.endsWith('Retrying in 30 s.')
        // the second view never reached the network
        http.requests.size() == 1
    }

    def "the background tries while page views are failing, and its success ends the window"() {
        given:
        def http = new StubHttp(answer: unreachable())
        def page = new InvaryAppraiserClient(APPRAISER, TOKEN, http, false, InvaryAppraiserClient.INTERACTIVE)
        def background = new InvaryAppraiserClient(APPRAISER, TOKEN, http, false)

        when:
        try { page.fetchFleetReport() } catch (RuntimeException ignored) { }
        http.answer = answered()
        Map report = background.fetchFleetReport()
        Map again = page.fetchFleetReport()

        then:
        report != null
        again != null
        http.requests.size() == 3
    }

    def "an appraiser that answers with an error status is reachable"() {
        given:
        def http = new StubHttp(answer: unreachable())
        def page = new InvaryAppraiserClient(APPRAISER, TOKEN, http, false, InvaryAppraiserClient.INTERACTIVE)
        def background = new InvaryAppraiserClient(APPRAISER, TOKEN, http, false)

        when:
        try { page.fetchFleetReport() } catch (RuntimeException ignored) { }
        http.answer = new ServiceResponse(success: false, errorCode: '500', content: 'broken')
        try { background.fetchFleetReport() } catch (RuntimeException ignored) { }

        then:
        InvaryAppraiserAvailability.unavailable(APPRAISER) == null
    }

    // -- Settings -------------------------------------------------------------

    @Unroll
    def "a checkbox stored as #value is #expected"() {
        expect:
        InvarySettings.checked(value) == expected

        where:
        value   || expected
        'on'    || true
        'true'  || true
        'TRUE'  || true
        true    || true
        'off'   || false
        'false' || false
        false   || false
        ''      || false
        null    || false
    }

    def "the warning follows the setting"() {
        expect:
        InvarySettings.appraiserWarning([ignoreAppraiserCertErrors: 'on']) == InvarySettings.IGNORE_CERT_ERRORS_WARNING
        InvarySettings.appraiserWarning([ignoreAppraiserCertErrors: 'off']) == null
        InvarySettings.appraiserWarning([:]) == null
    }

    def "no client is built without an API URL"() {
        expect:
        InvarySettings.appraiserClient(null, [appraiserApiToken: TOKEN]) == null
    }

    // -- Client reuse ---------------------------------------------------------

    def "the http client is reused until certificate verification changes"() {
        when:
        def verifying = InvaryHttpClients.get(null, false)
        def again = InvaryHttpClients.get(null, false)
        def ignoring = InvaryHttpClients.get(null, true)

        then:
        verifying.is(again)
        !ignoring.is(verifying)
    }
}
