// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import groovy.transform.ToString
import groovy.util.logging.Slf4j

/**
 * The settings a sensor installation needs, resolved from the plugin settings, the task
 * options and the instance being installed to.
 *
 * Each overridable setting is resolved by taking the task value unless it is `inherit`, then
 * the plugin setting unless it is blank, and finally the documented default. The appraiser API
 * URL and the Customer Package URL are plugin settings alone, which no task can override.
 */
@Slf4j
@ToString(includeNames = true, includePackage = false)
class InvarySensorConfig {

    // -- Sentinels and choices ------------------------------------------------

    /** A task level value that defers to the plugin setting. */
    static final String INHERIT = 'inherit'

    static final String REMEDIATION_NONE = 'none'
    static final List<String> REMEDIATION_ACTIONS = ['none', 'stop', 'restart', 'suspend', 'revert'].asImmutable()

    static final String TRUST_AUTO = 'auto'
    static final String TRUST_YES = 'yes'
    static final String TRUST_NO = 'no'
    static final List<String> CERT_TRUST_MODES = [TRUST_AUTO, TRUST_YES, TRUST_NO].asImmutable()

    /**
     * An interval that removes the key from the configuration rather than writing one, leaving
     * the sensor to measure at its own interval.
     *
     * Needed because an unset interval means "keep what the machine already has": without a
     * value that means "clear it", an interval set once could never be taken back through the
     * task.
     */
    static final int INTERVAL_CLEAR = -1

    // -- Plugin setting names, as declared by InvaryPlugin.getSettings() ------

    static final String SETTING_API_URL = 'appraiserUrl'
    static final String SETTING_API_TOKEN = 'appraiserApiToken'
    static final String SETTING_IGNORE_APPRAISER_CERT_ERRORS = 'ignoreAppraiserCertErrors'
    static final String SETTING_SENSOR_ALLOW_UNTRUSTED = 'sensorAllowUntrustedCerts'
    static final String SETTING_REMEDIATION = 'defaultRemediationAction'
    static final String SETTING_CUSTOMER_PACKAGE_URL = 'customerPackageUrl'

    // -- Task option codes, as declared by InvarySensorInstallTaskProvider ----

    static final String OPTION_TAGS = 'invary-sensor-tags'
    static final String OPTION_REMEDIATION = 'invary-sensor-remediation-action'
    static final String OPTION_INTERVAL = 'invary-sensor-measurement-interval'
    static final String OPTION_ALLOW_UNTRUSTED = 'invary-sensor-allow-untrusted-certs'

    // -- Tag prefixes shared with the appraiser's integration -----------------

    /** What every tag the plugin sets begins with, and nothing an operator sets does. */
    static final String TAG_PREFIX = 'hpe.morpheus.'

    static final String TAG_SERVER_PREFIX = "${TAG_PREFIX}server:"
    static final String TAG_INSTANCE_PREFIX = "${TAG_PREFIX}instance:"
    static final String TAG_REMEDIATE_PREFIX = "${TAG_PREFIX}remediate:"

    // -- Resolved values ------------------------------------------------------

    /** The HTTPS API URL of the appraiser, used to download packages. */
    String apiUrl

    /** The URL the installed sensor sends its measurements to. */
    String sensorUrl

    /** Whether the sensor should accept an untrusted appraiser certificate. */
    boolean allowUntrusted

    /** The tags to install the sensor with, the server the sensor runs on first. */
    List<String> tags

    /**
     * Seconds between measurements, {@link #INTERVAL_CLEAR} to remove any interval the machine
     * already has, or null when the install task asks for no interval of its own.
     *
     * Null keeps whatever the machine is already configured with. Operators appraise critical
     * machines more often than the rest by setting a shorter interval on those machines, so an
     * install that expresses no opinion must not take that away.
     */
    Integer measurementInterval

    /** The remediation action recorded in the tags, retained for logging. */
    String remediationAction

    /**
     * The URL of the customer package the repo definition package is taken from.
     *
     * The Invary package repository is password protected on every channel, and the credentials
     * differ between customers, so the repository can only be configured from the package Invary
     * issued to this customer.
     */
    String customerPackageUrl

    // -- Resolution -----------------------------------------------------------

    /**
     * Builds the configuration for an installation.
     *
     * @param settings the plugin settings, keyed by field name
     * @param options the task option values, keyed by option type code
     * @param serverId the Morpheus server the sensor is being installed on
     * @param instanceId the Morpheus instance that server belongs to, or null when it belongs
     *                   to none
     * @param discovery supplies the appraiser's /config response, called at most once and
     *                  only when a setting actually needs it
     * @throws IllegalArgumentException if a value is missing or malformed
     */
    static InvarySensorConfig resolve(Map settings, Map options, Long serverId, Long instanceId, Closure<Map> discovery) {
        settings = settings ?: [:]
        options = options ?: [:]

        String apiUrl = trimUrl(settings[SETTING_API_URL])
        if (!apiUrl) {
            throw new IllegalArgumentException('The Invary Appraiser API URL is not configured. Set it in the plugin settings.')
        }

        if (!serverId) {
            throw new IllegalArgumentException('The Morpheus server could not be determined, so the sensor could not be given an identity tag.')
        }

        // the appraiser reports both the sensor URL, which nothing else supplies, and whether its
        // certificate is self signed, which the trust mode falls back on. One round trip serves
        // both, and the trust mode makes none of its own when it is set explicitly
        Map discovered = null
        Closure<Map> discoverOnce = {
            if (discovered == null) {
                discovered = discovery ? discovery.call() : null
                if (discovered == null) {
                    throw new IllegalArgumentException(
                        "The Invary Appraiser configuration could not be read from ${apiUrl}/api/morpheus/config. " +
                        'A sensor is installed with the URL the appraiser reports there, so the appraiser has to be ' +
                        'reachable to install one.')
                }
            }
            return discovered
        }

        String sensorUrl = sensorEndpointUrl(discoverOnce.call().sensor_endpoint_url, apiUrl)

        String trustMode = resolveChoice(options[OPTION_ALLOW_UNTRUSTED], settings[SETTING_SENSOR_ALLOW_UNTRUSTED],
                TRUST_AUTO, CERT_TRUST_MODES, 'Sensor Allow Untrusted Certs')
        boolean allowUntrusted = (trustMode == TRUST_AUTO)
                ? (discoverOnce.call().uses_self_signed_cert as boolean)
                : (trustMode == TRUST_YES)

        String remediation = resolveChoice(options[OPTION_REMEDIATION], settings[SETTING_REMEDIATION],
                REMEDIATION_NONE, REMEDIATION_ACTIONS, 'Remediation Action')

        return new InvarySensorConfig(
            apiUrl: apiUrl,
            sensorUrl: sensorUrl,
            allowUntrusted: allowUntrusted,
            remediationAction: remediation,
            measurementInterval: resolveInterval(options[OPTION_INTERVAL]),
            tags: buildTags(serverId, instanceId, remediation, options[OPTION_TAGS]),
            customerPackageUrl: customerPackageUrl(settings[SETTING_CUSTOMER_PACKAGE_URL]),
        )
    }

    /** Printable ASCII other than space, the only characters a sensor endpoint URL may hold. */
    private static final String URL_CHARACTERS = /^[\x21-\x7e]+$/

    /** A host name of letters, digits and hyphens, or a bracketed IPv6 literal. */
    private static final String URL_HOST = /^(?:[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*|\[[0-9A-Fa-f:.]+\])$/

    /** Segments of unreserved characters, so nothing the shell or TOML treat specially. */
    private static final String URL_PATH = /^(?:\/[A-Za-z0-9._~-]+)*\/?$/

    /** How much of a rejected value is quoted back, so a hostile one cannot flood the output. */
    private static final int QUOTED_LENGTH = 100

    /**
     * Checks the sensor endpoint URL the appraiser reported, which is written into the sensor
     * configuration and shown by the install script, so it is held to what an appraiser
     * produces: `http(s)://host[:port]`, with room for a path of plain segments for an appraiser
     * served behind a reverse proxy.
     *
     * Stricter than {@link URI}, which accepts `$`, quotes, parentheses and `;` in a host name.
     *
     * @throws IllegalArgumentException if the appraiser reported none, or one that is not of that
     *         form
     */
    static String sensorEndpointUrl(Object reported, String apiUrl) {
        String value = trimUrl(reported)
        if (!value) {
            throw new IllegalArgumentException("The Invary Appraiser at ${apiUrl} did not report a sensor endpoint URL.")
        }

        if (!validSensorEndpointUrl(value)) {
            throw new IllegalArgumentException(
                "The Invary Appraiser at ${apiUrl} reported a sensor endpoint URL that is not an " +
                "http(s)://host[:port][/path] URL: '${quoted(value)}'.")
        }

        return value
    }

    private static boolean validSensorEndpointUrl(String value) {
        // checked before parsing, so that URI never decodes or tolerates anything of its own
        if (!(value ==~ URL_CHARACTERS) || value.contains('%')) {
            return false
        }

        URI uri
        try {
            uri = new URI(value)
        } catch (URISyntaxException ignored) {
            return false
        }

        // URI leaves the host null when it is not a server-based authority, and leaves
        // everything after the scheme unparsed for an opaque URI such as `http:host`
        String scheme = uri.scheme?.toLowerCase()
        String authority = uri.rawAuthority
        if (!(scheme in ['http', 'https']) || uri.opaque || !authority) {
            return false
        }

        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
            return false
        }

        // the host and port are read from the authority itself, which URI would otherwise
        // accept as a registry name it cannot split
        def parts = authority =~ /^(\[[^\]]*\]|[^:\[\]]*)(?::([0-9]{1,5}))?$/
        if (!parts.matches() || !(parts.group(1) ==~ URL_HOST)) {
            return false
        }

        String port = parts.group(2)
        if (port != null && !((port as int) in 1..65535)) {
            return false
        }

        String path = uri.rawPath ?: ''
        return path ==~ URL_PATH && !path.split('/').any { it in ['.', '..'] }
    }

    /** A rejected value made safe to show: printable, and no longer than {@link #QUOTED_LENGTH}. */
    private static String quoted(String value) {
        String printable = value.replaceAll(/[^\x20-\x7e]/, '?')
        return printable.length() > QUOTED_LENGTH ? printable.take(QUOTED_LENGTH) + '...' : printable
    }

    /**
     * Resolves the Customer Package URL from the plugin settings alone. No task can override it,
     * since the appliance fetches it from its own network position, and only an administrator
     * can edit the plugin settings.
     *
     * The URL must be https: the package it names configures the repository the sensor is
     * installed from, with that repository's credentials.
     *
     * @throws IllegalArgumentException if it is not configured, or is not an https URL
     */
    private static String customerPackageUrl(Object setting) {
        String value = trimUrl(setting)
        if (!value) {
            throw new IllegalArgumentException(
                'Customer Package URL is not configured. Set it to the URL of your Invary Customer ' +
                'Package in the plugin settings.')
        }

        if (!(value ==~ /(?i)^https:\/\/.+/)) {
            throw new IllegalArgumentException(
                "Customer Package URL must be an https URL, but was '${quoted(value)}'.")
        }

        return value
    }

    /**
     * Assembles the tag list. The sensor measures one machine, so the server tag is its identity
     * to the appraiser and is always present. The instance tag records the service that machine
     * is part of, which not every machine is.
     */
    static List<String> buildTags(Long serverId, Long instanceId, String remediation, Object extra) {
        List<String> tags = ["${TAG_SERVER_PREFIX}${serverId}".toString()]

        if (instanceId) {
            tags << "${TAG_INSTANCE_PREFIX}${instanceId}".toString()
        }

        if (remediation && remediation != REMEDIATION_NONE) {
            tags << "${TAG_REMEDIATE_PREFIX}${remediation}".toString()
        }

        blank(extra) ? tags : tags + splitTags(extra)
    }

    static List<String> splitTags(Object value) {
        return value.toString().split(',').collect { it.trim() }.findAll { it }
    }

    /** Resolves a value constrained to a fixed set of choices. */
    private static String resolveChoice(Object option, Object setting, String fallback, List<String> allowed, String label) {
        String value = inherited(option) ? text(setting) : text(option)
        if (!value) {
            return fallback
        }

        value = value.toLowerCase()
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException("${label} must be one of ${allowed.join(', ')}, but was '${value}'.")
        }

        return value
    }

    /**
     * Resolves the measurement interval into one of three states: a positive number of seconds
     * to write, {@link #INTERVAL_CLEAR} to remove whatever the machine has, or null to keep it.
     *
     * The interval is the install task's alone - no plugin setting stands behind it - so an
     * option left at {@link #INHERIT} writes nothing, and a machine with no interval of its own
     * is left measuring at the interval the sensor defaults to.
     */
    private static Integer resolveInterval(Object option) {
        String value = inherited(option) ? null : text(option)
        if (!value) {
            return null
        }

        if (!value.isInteger() || ((value as int) <= 0 && (value as int) != INTERVAL_CLEAR)) {
            throw new IllegalArgumentException(
                "Measurement Interval must be '${INHERIT}' to keep the interval a machine already has, " +
                "a positive number of seconds, or ${INTERVAL_CLEAR} to clear it, but was '${value}'.")
        }

        return value as int
    }

    /** A task option that is blank or the inherit sentinel defers to the plugin setting. */
    private static boolean inherited(Object option) {
        String value = text(option)
        return !value || value.equalsIgnoreCase(INHERIT)
    }

    private static boolean blank(Object value) {
        return !text(value)
    }

    private static String text(Object value) {
        return value?.toString()?.trim() ?: null
    }

    /** Normalizes a configured URL, dropping any trailing slashes. */
    private static String trimUrl(Object value) {
        return text(value)?.replaceAll('/+$', '') ?: null
    }
}
