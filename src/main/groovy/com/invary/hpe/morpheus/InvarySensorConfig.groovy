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
 * the plugin setting unless it is blank, and finally the documented default.
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
    static final String SETTING_ALLOW_UNTRUSTED = 'allowUntrustedCerts'
    static final String SETTING_REMEDIATION = 'defaultRemediationAction'
    static final String SETTING_CUSTOMER_PACKAGE_URL = 'customerPackageUrl'

    // -- Task option codes, as declared by InvarySensorInstallTaskProvider ----

    static final String OPTION_TAGS = 'invary-sensor-tags'
    static final String OPTION_REMEDIATION = 'invary-sensor-remediation-action'
    static final String OPTION_INTERVAL = 'invary-sensor-measurement-interval'
    static final String OPTION_ALLOW_UNTRUSTED = 'invary-sensor-allow-untrusted-certs'
    static final String OPTION_CUSTOMER_PACKAGE_URL = 'invary-sensor-customer-package-url'

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

        String sensorUrl = trimUrl(discoverOnce.call().sensor_endpoint_url)
        if (!sensorUrl) {
            throw new IllegalArgumentException("The Invary Appraiser at ${apiUrl} did not report a sensor endpoint URL.")
        }

        String trustMode = resolveChoice(options[OPTION_ALLOW_UNTRUSTED], settings[SETTING_ALLOW_UNTRUSTED],
                TRUST_AUTO, CERT_TRUST_MODES, 'Allow Untrusted Certs')
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
            customerPackageUrl: resolveUrl(options[OPTION_CUSTOMER_PACKAGE_URL],
                settings[SETTING_CUSTOMER_PACKAGE_URL], 'Customer Package URL'),
        )
    }

    /**
     * Resolves a URL the task may override and the plugin settings stand behind.
     *
     * @throws IllegalArgumentException if neither supplies one, or what they supply is not an
     *         http or https URL
     */
    private static String resolveUrl(Object option, Object setting, String label) {
        String value = trimUrl(inherited(option) ? setting : option)
        if (!value) {
            throw new IllegalArgumentException(
                "${label} is not configured. Set it to the URL of your Invary Customer Package " +
                'in the plugin settings or on this task.')
        }

        if (!(value ==~ /(?i)^https?:\/\/.+/)) {
            throw new IllegalArgumentException(
                "${label} must be an http or https URL, but was '${value}'.")
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
