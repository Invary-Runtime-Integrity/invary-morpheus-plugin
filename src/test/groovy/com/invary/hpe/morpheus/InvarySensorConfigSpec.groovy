// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import spock.lang.Specification
import spock.lang.Unroll

class InvarySensorConfigSpec extends Specification {

    static final Long SERVER_ID = 7L
    static final Long INSTANCE_ID = 21L

    static final Map DISCOVERED = [
        sensor_endpoint_url: 'https://192.0.2.10:7386',
        uses_self_signed_cert: true,
    ]

    static final String CUSTOMER_PACKAGE_URL = 'https://static.invary.com/package/invary-core-test.tar.gz'

    /** Settings with the trust mode explicit, so only the sensor URL is discovered. */
    static Map settings(Map overrides = [:]) {
        return [
            appraiserUrl: 'https://192.0.2.10:8443',
            sensorAllowUntrustedCerts:'no',
            customerPackageUrl: CUSTOMER_PACKAGE_URL,
        ] + overrides
    }

    static Closure<Map> discovery(Map result = DISCOVERED) {
        return { result }
    }

    static InvarySensorConfig resolve(Map settings, Map options = [:], Closure<Map> discovery = discovery()) {
        return InvarySensorConfig.resolve(settings, options, SERVER_ID, INSTANCE_ID, discovery)
    }

    // -- Inheritance ----------------------------------------------------------

    def "a task value of inherit falls through to the plugin setting"() {
        when:
        def config = resolve(settings(defaultRemediationAction: 'revert'),
            [(InvarySensorConfig.OPTION_REMEDIATION): 'inherit'])

        then:
        config.remediationAction == 'revert'
    }

    def "a task value overrides the plugin setting"() {
        when:
        def config = resolve(settings(defaultRemediationAction: 'revert'),
            [(InvarySensorConfig.OPTION_REMEDIATION): 'stop'])

        then:
        config.remediationAction == 'stop'
    }

    def "an absent task option is treated as inherit"() {
        when:
        def config = resolve(settings(defaultRemediationAction: 'revert'))

        then:
        config.remediationAction == 'revert'
    }

    // -- Tags -----------------------------------------------------------------

    def "the server a sensor runs on is its identity, and the instance is recorded with it"() {
        when:
        def config = resolve(settings())

        then:
        config.tags == ['hpe.morpheus.server:7', 'hpe.morpheus.instance:21']
    }

    def "a server that belongs to no instance is tagged by server alone"() {
        when:
        def config = InvarySensorConfig.resolve(settings(), [:], SERVER_ID, null, discovery())

        then:
        config.tags == ['hpe.morpheus.server:7']
    }

    def "a remediation action adds a remediate tag"() {
        when:
        def config = resolve(settings(defaultRemediationAction: 'revert'))

        then:
        config.tags == ['hpe.morpheus.server:7', 'hpe.morpheus.instance:21', 'hpe.morpheus.remediate:revert']
    }

    def "a task action of none overrides a plugin action and suppresses the remediate tag"() {
        when:
        def config = resolve(settings(defaultRemediationAction: 'revert'),
            [(InvarySensorConfig.OPTION_REMEDIATION): 'none'])

        then:
        config.remediationAction == 'none'
        config.tags == ['hpe.morpheus.server:7', 'hpe.morpheus.instance:21']
    }

    def "task tags are appended after the generated tags"() {
        when:
        def config = resolve(settings(defaultRemediationAction: 'stop'),
            [(InvarySensorConfig.OPTION_TAGS): 'production, web-tier ,, east'])

        then:
        config.tags == ['hpe.morpheus.server:7', 'hpe.morpheus.instance:21', 'hpe.morpheus.remediate:stop', 'production', 'web-tier', 'east']
    }

    def "an unknown server is rejected rather than installing an uncorrelated sensor"() {
        when:
        InvarySensorConfig.resolve(settings(), [:], null, INSTANCE_ID, discovery())

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('server')
    }

    def "remediation is armed on a server that belongs to no instance"() {
        when:
        def config = InvarySensorConfig.resolve(settings(defaultRemediationAction: 'restart'), [:], SERVER_ID, null, discovery())

        then:
        // the appraiser remediates the machine that failed, so no instance is needed
        config.tags == ['hpe.morpheus.server:7', 'hpe.morpheus.remediate:restart']
    }

    // -- Certificate trust ----------------------------------------------------

    def "auto trust mode takes the self signed flag from the appraiser"() {
        when:
        def config = resolve(settings(sensorAllowUntrustedCerts:'auto'), [:], discovery(selfSigned))

        then:
        config.allowUntrusted == expected

        where:
        selfSigned                                                  || expected
        [sensor_endpoint_url: 'https://a:7386', uses_self_signed_cert: true]  || true
        [sensor_endpoint_url: 'https://a:7386', uses_self_signed_cert: false] || false
    }

    @Unroll
    def "an explicit trust mode of #mode ignores what the appraiser reports"() {
        when:
        def config = resolve(settings(sensorAllowUntrustedCerts:mode))

        then:
        config.allowUntrusted == expected

        where:
        mode  || expected
        'yes' || true
        'no'  || false
    }

    def "a task trust mode overrides the plugin setting"() {
        when:
        def config = resolve(settings(sensorAllowUntrustedCerts:'no'),
            [(InvarySensorConfig.OPTION_ALLOW_UNTRUSTED): 'yes'])

        then:
        config.allowUntrusted
    }

    // -- Discovery ------------------------------------------------------------

    def "discovery supplies the sensor url"() {
        when:
        def config = resolve(settings())

        then:
        config.sensorUrl == 'https://192.0.2.10:7386'
    }

    def "discovery runs once even when two settings need it"() {
        given:
        int calls = 0

        when:
        def config = resolve(settings(sensorAllowUntrustedCerts:'auto'), [:], { calls++; DISCOVERED })

        then:
        calls == 1
        config.sensorUrl == 'https://192.0.2.10:7386'
        config.allowUntrusted
    }

    def "an unreachable appraiser is reported rather than guessing a port"() {
        when:
        resolve(settings(), [:], { null })

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('/config')
    }

    // -- URLs -----------------------------------------------------------------

    def "a missing api url is rejected"() {
        when:
        resolve(settings(appraiserUrl: '  '))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('API URL')
    }

    def "trailing slashes are trimmed from both urls"() {
        when:
        def config = resolve(settings(appraiserUrl: 'https://a:8443//'), [:],
            discovery([sensor_endpoint_url: 'https://a:7386/', uses_self_signed_cert: false]))

        then:
        config.apiUrl == 'https://a:8443'
        config.sensorUrl == 'https://a:7386'
    }

    @Unroll
    def "a reported sensor url of #value is accepted"() {
        when:
        def config = resolve(settings(), [:], discovery([sensor_endpoint_url: value]))

        then:
        config.sensorUrl == expected

        where:
        value                                  || expected
        'https://host.example:7386'            || 'https://host.example:7386'
        'http://10.0.0.1:7386'                 || 'http://10.0.0.1:7386'
        'https://[fd00::1]:7386'               || 'https://[fd00::1]:7386'
        'HTTPS://Host-1.example:7386'          || 'HTTPS://Host-1.example:7386'
        'https://host.example:7386/'           || 'https://host.example:7386'
        'https://host.example'                 || 'https://host.example'
        'https://host.example/invary/sensors'  || 'https://host.example/invary/sensors'
        'https://host.example:443/a_b.c~d-e/'  || 'https://host.example:443/a_b.c~d-e'
    }

    @Unroll
    def "a reported sensor url holding #described is refused"() {
        when:
        resolve(settings(), [:], discovery([sensor_endpoint_url: value]))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('reported a sensor endpoint URL that is not an http(s)://host[:port][/path] URL')

        where:
        described                | value
        'command substitution'   | 'https://$(id).example:7386'
        'a backtick'             | 'https://`id`.example:7386'
        'a semicolon'            | 'https://a.example:7386;id'
        'a pipe'                 | 'https://a.example:7386|id'
        'an ampersand'           | 'https://a.example:7386&id'
        'a double quote'         | 'https://a.example:7386"'
        'a single quote'         | "https://a.example:7386'"
        'a space'                | 'https://a.example:7386 x'
        'a newline'              | 'https://a.example:7386\nid'
        'a NUL'                  | 'https://a.example' + String.valueOf((char) 0) + ':7386'
        'user info'              | 'https://u:p@a.example:7386'
        'a query'                | 'https://a.example:7386?x=1'
        'a fragment'             | 'https://a.example:7386#x'
        'a percent escape'       | 'https://a.example:7386/%24%28id%29'
        'a dot dot segment'      | 'https://a.example:7386/../x'
        'a dot segment'          | 'https://a.example:7386/./x'
        'an empty segment'       | 'https://a.example:7386//x'
        'a substituted path'     | 'https://a.example:7386/$(id)'
        'another scheme'         | 'ftp://a.example:7386'
        'a file url'             | 'file:/etc/passwd'
        'an opaque url'          | 'http:a.example'
        'no scheme'              | 'a.example:7386'
        'an empty host'          | 'https://:7386'
        'an empty port'          | 'https://a.example:'
        'port zero'              | 'https://a.example:0'
        'a port too large'       | 'https://a.example:70000'
        'an underscore host'     | 'https://a_b.example:7386'
        'an unbracketed ipv6'    | 'https://fd00::1:7386'
    }

    def "a refused sensor url is quoted back printable and shortened"() {
        when:
        resolve(settings(), [:], discovery([sensor_endpoint_url: 'https://a.example:7386\nid' + ('x' * 200)]))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("'https://a.example:7386?id")
        !e.message.contains('\n')
        e.message.endsWith("...'.")
    }

    def "a sensor url the appraiser did not report is refused"() {
        when:
        resolve(settings(), [:], discovery([uses_self_signed_cert: false]))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('did not report a sensor endpoint URL')
    }

    // -- Validation -----------------------------------------------------------

    @Unroll
    def "measurement interval rejects #value"() {
        when:
        resolve(settings(), [(InvarySensorConfig.OPTION_INTERVAL): value])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('Measurement Interval')

        where:
        value << ['soon', '0', '-30', '5.5']
    }

    @Unroll
    def "remediation action rejects #value"() {
        when:
        resolve(settings(), [(InvarySensorConfig.OPTION_REMEDIATION): value])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('Remediation Action')

        where:
        value << ['destroy', 'reboot', 'yes']
    }

    def "choices are matched case insensitively"() {
        when:
        def config = resolve(settings(), [(InvarySensorConfig.OPTION_REMEDIATION): 'Revert'])

        then:
        config.remediationAction == 'revert'
        config.tags.contains('hpe.morpheus.remediate:revert')
    }

    // -- Measurement interval: keep, set, clear -------------------------------

    def "no interval anywhere keeps whatever the machine has"() {
        expect:
        resolve(settings()).measurementInterval == null
    }

    def "an interval of inherit is not inherited from anywhere, and keeps what the machine has"() {
        expect:
        resolve(settings(), [(InvarySensorConfig.OPTION_INTERVAL): 'inherit']).measurementInterval == null
    }

    def "an interval is resolved from the task option"() {
        expect:
        resolve(settings(), [(InvarySensorConfig.OPTION_INTERVAL): '300']).measurementInterval == 300
    }

    def "minus one is the value that clears an interval"() {
        expect:
        resolve(settings(), [(InvarySensorConfig.OPTION_INTERVAL): '-1']).measurementInterval ==
            InvarySensorConfig.INTERVAL_CLEAR
    }

    @Unroll
    def "an interval of #value is refused"() {
        when:
        resolve(settings(), [(InvarySensorConfig.OPTION_INTERVAL): value])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('Measurement Interval')

        where:
        value << ['0', '-2', 'often', '1.5']
    }

    // -- Customer package url -------------------------------------------------

    def "the customer package url comes from the plugin setting"() {
        expect:
        resolve(settings()).customerPackageUrl == CUSTOMER_PACKAGE_URL
    }

    def "a task option cannot override the customer package url"() {
        expect:
        // the appliance fetches the url, so only the administrator's plugin setting may name it;
        // this is the code the task option had before it was removed
        resolve(settings(), ['invary-sensor-customer-package-url': 'https://internal.corp/metadata'])
            .customerPackageUrl == CUSTOMER_PACKAGE_URL
    }

    def "a trailing slash is trimmed from the customer package url"() {
        expect:
        resolve(settings(customerPackageUrl: 'https://artifacts.corp/invary.tar.gz/'))
            .customerPackageUrl == 'https://artifacts.corp/invary.tar.gz'
    }

    def "a customer package url configured nowhere is refused"() {
        when:
        resolve(settings(customerPackageUrl: null))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('Customer Package URL')
    }

    @Unroll
    def "a customer package url of #value is refused as not a url"() {
        when:
        resolve(settings(customerPackageUrl: value))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('http')

        where:
        // a path to a file on the appliance is the likely mistake, and nothing fetches one
        value << ['/srv/invary/customer.tar.gz', 'ftp://invary/customer.tar.gz', 'static.invary.com/x.tar.gz']
    }

    def "a customer package url must be https"() {
        when:
        resolve(settings(customerPackageUrl: 'http://static.invary.com/package/invary-core-test.tar.gz'))

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "Customer Package URL must be an https URL, but was " +
                     "'http://static.invary.com/package/invary-core-test.tar.gz'."
    }

}
