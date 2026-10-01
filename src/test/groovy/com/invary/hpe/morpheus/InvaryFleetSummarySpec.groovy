// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import spock.lang.Specification

/**
 * Covers the tenant scoping of the fleet report. One appraiser serves the whole appliance, so
 * what a tenant is shown is decided here rather than by the appraiser.
 */
class InvaryFleetSummarySpec extends Specification {

    static Map entry(Object serverId, String status = 'successful', boolean offline = false) {
        return [server_id: serverId, name: "server-${serverId}", status: status, offline: offline]
    }

    // -- Scoping --------------------------------------------------------------

    def "only the entries whose server the tenant owns are kept"() {
        given:
        List entries = [entry(1), entry(2), entry(3)]

        expect:
        InvaryFleetSummary.scopeToVisible(entries, ['1', '3'] as Set)*.server_id == [1, 3]
    }

    def "a tenant that owns none of the appraised machines is shown none of them"() {
        expect:
        InvaryFleetSummary.scopeToVisible([entry(1), entry(2)], [] as Set) == []
    }

    def "an entry naming no server is left out rather than shown to every tenant"() {
        expect:
        InvaryFleetSummary.scopeToVisible([entry(null), entry(1)], ['1'] as Set)*.server_id == [1]
    }

    def "server ids are matched as text, whatever the appraiser reported them as"() {
        expect:
        InvaryFleetSummary.scopeToVisible([entry(42L), entry('43')], ['42', '43'] as Set).size() == 2
    }

    // -- Counting -------------------------------------------------------------

    def "the counts describe the scoped rows rather than the whole appliance"() {
        given:
        List rows = [
            [status: 'successful', offline: false],
            [status: 'successful', offline: false],
            [status: 'failed', offline: false],
            [status: 'successful', offline: true],
        ]

        expect:
        InvaryFleetSummary.counts(rows) == [total: 4, passing: 2, failing: 1, offline: 1]
    }

    def "a machine that stopped reporting is counted offline, not by its last appraisal"() {
        expect:
        InvaryFleetSummary.counts([[status: 'failed', offline: true]]) ==
            [total: 1, passing: 0, failing: 0, offline: 1]
    }

    def "an errored appraisal is counted in the total alone, as the failing list leaves it out"() {
        expect:
        InvaryFleetSummary.counts([[status: 'errored', offline: false]]) ==
            [total: 1, passing: 0, failing: 0, offline: 0]
    }

    def "no rows counts as an empty fleet rather than failing"() {
        expect:
        InvaryFleetSummary.counts([]) == [total: 0, passing: 0, failing: 0, offline: 0]
        InvaryFleetSummary.fleetStatus(InvaryFleetSummary.counts([]) as Map) == 'UNKNOWN'
    }
}
