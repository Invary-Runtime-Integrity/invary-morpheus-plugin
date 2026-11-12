// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import spock.lang.Specification

class InvaryAppraiserAvailabilitySpec extends Specification {

    static final String APPRAISER = 'https://appraiser.example:8443'
    static final long T0 = 1_000_000L

    def cleanup() {
        InvaryAppraiserAvailability.clear()
    }

    def "an appraiser nothing has failed to reach is available"() {
        expect:
        InvaryAppraiserAvailability.unavailable(APPRAISER, T0) == null
    }

    def "a failure is reported with its age, its reason and when it will be tried again"() {
        given:
        InvaryAppraiserAvailability.failed(APPRAISER, 'Connect timed out', T0)

        expect:
        InvaryAppraiserAvailability.unavailable(APPRAISER, T0 + 12_500) ==
            "The Invary Appraiser at ${APPRAISER} could not be reached 12 s ago (Connect timed out). Retrying in 18 s."
    }

    def "a failure with no reason still says what happened"() {
        given:
        InvaryAppraiserAvailability.failed(APPRAISER, null, T0)

        expect:
        InvaryAppraiserAvailability.unavailable(APPRAISER, T0).contains('(no reason was reported)')
    }

    def "the window ends thirty seconds after the failure"() {
        given:
        InvaryAppraiserAvailability.failed(APPRAISER, 'Connect timed out', T0)

        expect:
        InvaryAppraiserAvailability.unavailable(APPRAISER, T0 + InvaryAppraiserAvailability.WINDOW_MILLIS - 1) != null
        InvaryAppraiserAvailability.unavailable(APPRAISER, T0 + InvaryAppraiserAvailability.WINDOW_MILLIS) == null

        and:
        // an expired failure is forgotten rather than coming back
        InvaryAppraiserAvailability.unavailable(APPRAISER, T0) == null
    }

    def "reaching the appraiser ends the window at once"() {
        given:
        InvaryAppraiserAvailability.failed(APPRAISER, 'Connect timed out', T0)

        when:
        InvaryAppraiserAvailability.reached(APPRAISER)

        then:
        InvaryAppraiserAvailability.unavailable(APPRAISER, T0) == null
    }

    def "each appraiser is tracked apart"() {
        given:
        InvaryAppraiserAvailability.failed(APPRAISER, 'Connect timed out', T0)

        expect:
        InvaryAppraiserAvailability.unavailable('https://other.example:8443', T0) == null
        InvaryAppraiserAvailability.unavailable(APPRAISER, T0) != null
    }
}
