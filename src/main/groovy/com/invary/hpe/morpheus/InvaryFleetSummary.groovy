// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Account
import groovy.util.logging.Slf4j

/**
 * Reads the fleet report the appraiser serves at GET /api/morpheus/report and puts it in the shape the
 * Analytics section shows it in. A row is a Morpheus knows as a server.
 *
 * The InvaryCustomReportProvider does a version of this same work (transforming /api/morpheus/report),
 * but it renders a row per machine and includes kernel and sensor details, so it should 
 * remain separate.
 */
@Slf4j
class InvaryFleetSummary {

    /** How many instances a table lists before it says how many more there are. */
    static final int LIST_LIMIT = 100

    /**
     * Loads the summary for one tenant. The result always holds the counts and the list, so a
     * widget can render it without checking, and carries `error` when the appraiser could not be
     * read.
     *
     * @param account the tenant the summary is for. One appraiser serves the whole appliance, so
     *                its report covers every tenant and is scoped here to the machines this one
     *                owns. A null account is scoped to nothing rather than to everything.
     */
    static Map load(MorpheusContext morpheus, Plugin plugin, Account account) {
        Map summary = empty()

        try {
            InvaryAppraiserClient client = InvarySettings.appraiserClient(morpheus, plugin)
            if (!client) {
                summary.error = 'The Invary Appraiser API URL is not configured.'
                return summary
            }

            Map report = client.fetchFleetReport()
            if (report == null) {
                summary.error = 'The Invary Appraiser did not report any integrity results.'
                return summary
            }

            List scoped = scopeToAccount(morpheus, account, (report.endpoints ?: []) as List)
            summary.endpoints = scoped.collect { entry -> endpoint(entry as Map) }
            summary.putAll(counts(summary.endpoints as List))
        } catch (Exception e) {
            log.error("Could not read the Invary fleet report: ${e.message}", e)
            summary = empty()
            summary.error = "Could not read the Invary fleet report: ${e.message}"
        }

        return summary
    }

    /**
     * Keeps the fleet report entries that report on a machine the tenant owns.
     *
     * The appraiser serves one report for the whole appliance, and every entry it holds names the
     * Morpheus server it was measured on, so the tenant a row belongs to is the tenant of that
     * server. Morpheus is asked which servers those are rather than the account being compared
     * here, so a row is kept only when the platform itself says the tenant may see it.
     */
    static List scopeToAccount(MorpheusContext morpheus, Account account, List entries) {
        if (!account) {
            log.warn('No tenant was supplied for the Invary fleet report, so no machines are reported')
            return []
        }

        return scopeToVisible(entries, visibleServerIds(morpheus, account))
    }

    /** Keeps the entries whose server is one of the given ids. */
    static List scopeToVisible(List entries, Set<String> visibleServerIds) {
        return (entries ?: []).findAll { entry ->
            String serverId = ((Map) entry)?.server_id?.toString()
            return serverId && visibleServerIds.contains(serverId)
        }
    }

    /** The ids of the Morpheus servers the tenant may see, as Morpheus reports them. */
    static Set<String> visibleServerIds(MorpheusContext morpheus, Account account) {
        List projections = morpheus.async.computeServer
            .listIdentityProjections(new DataQuery(account))
            .toList()
            .blockingGet()

        return projections.collect { it.id?.toString() }.findAll { it } as Set
    }

    /**
     * Counts the scoped rows, which the appraiser cannot do for a tenant: its own totals cover the
     * whole appliance. A row that is neither passing, failing nor offline -- an errored or unknown
     * appraisal -- is counted in the total alone, as the lists of failing and offline machines
     * leave it out too.
     */
    static Map counts(List endpoints) {
        List rows = endpoints ?: []

        return [
            total  : rows.size(),
            passing: rows.count { !it.offline && it.status == 'successful' },
            failing: rows.count { !it.offline && it.status == 'failed' },
            offline: rows.count { it.offline },
        ]
    }

    static Map empty() {
        return [error: null, total: 0, passing: 0, failing: 0, offline: 0, endpoints: []]
    }

    /**
     * The state of the fleet as a whole: PASSED when every instance passed its last appraisal and
     * all of them are reporting, FAILED when every one of them failed, DEGRADED when some failed
     * or some stopped reporting, and UNKNOWN when nothing has been appraised.
     */
    static String fleetStatus(int total, int failing, int offline) {
        if (total == 0) {
            return 'UNKNOWN'
        }
        if (failing >= total) {
            return 'FAILED'
        }
        return failing > 0 || offline > 0 ? 'DEGRADED' : 'PASSED'
    }

    static String fleetStatus(Map summary) {
        return fleetStatus(count(summary.total), count(summary.failing), count(summary.offline))
    }

    /** The class the banner of a fleet in the given state is drawn with. */
    static String bannerClass(String fleetStatus) {
        return fleetStatus == 'PASSED' ? 'fleet-ok' : (fleetStatus == 'FAILED' ? 'fleet-critical' : 'fleet-degraded')
    }

    /**
     * The most recent appraisal of the fleet, as an instant. The appraiser records an appraisal
     * for every instance it knows of, so the latest of them is when the fleet was last measured.
     *
     * @return the instant, or null when nothing has been appraised
     */
    static String latestAppraisal(Map summary) {
        return (summary.endpoints ?: []).collect { it.lastAppraisalIso }.findAll { it }.max()
    }

    /**
     * The instances that failed their last appraisal, most recently appraised first. An offline
     * instance is left out: what its last appraisal said is no longer pertinent.
     *
     * @param limit how many to keep, the rest being counted by {@link #overflow}
     */
    static List failing(Map summary, int limit = LIST_LIMIT) {
        List failing = (summary.endpoints ?: []).findAll { it.status == 'failed' && !it.offline }
        failing = failing.sort { a, b -> (b.lastAppraisalIso ?: '') <=> (a.lastAppraisalIso ?: '') }
        return limit > 0 && failing.size() > limit ? failing.take(limit) : failing
    }

    /** How many failing instances a list of the given limit does not show. */
    static int overflow(Map summary, int limit = LIST_LIMIT) {
        int failing = (summary.endpoints ?: []).count { it.status == 'failed' && !it.offline }
        return limit > 0 && failing > limit ? failing - limit : 0
    }

    /**
     * The instances that have stopped reporting, most recently appraised first. Their last
     * appraisal is listed with them, since for one that was failing it is the last thing known.
     *
     * @param limit how many to keep, the rest being counted by {@link #offlineOverflow}
     */
    static List offline(Map summary, int limit = LIST_LIMIT) {
        List offline = (summary.endpoints ?: []).findAll { it.offline }
        offline = offline.sort { a, b -> (b.lastAppraisalIso ?: '') <=> (a.lastAppraisalIso ?: '') }
        return limit > 0 && offline.size() > limit ? offline.take(limit) : offline
    }

    /** How many offline instances a list of the given limit does not show. */
    static int offlineOverflow(Map summary, int limit = LIST_LIMIT) {
        int offline = (summary.endpoints ?: []).count { it.offline }
        return limit > 0 && offline > limit ? offline - limit : 0
    }

    private static Map endpoint(Map entry) {
        String serverId = entry.server_id?.toString()
        String instanceId = entry.instance_id?.toString()

        return [
            endpointId      : entry.endpoint?.toString(),
            serverId        : serverId,
            serverUrl       : serverUrl(serverId),
            instanceId      : instanceId,
            instanceUrl     : instanceUrl(instanceId),
            // a row leads to the machine it reports on, falling back to the service it is part
            // of for a machine appraised before servers were tracked
            url             : serverUrl(serverId) ?: instanceUrl(instanceId),
            name            : entry.name?.toString(),
            ip              : entry.ip_addr?.toString(),
            status          : entry.status?.toString() ?: 'unknown',
            offline         : entry.offline == true,
            checks          : count(entry.num_checks),
            failures        : count(entry.num_failures),
            lastAppraisal   : InvaryTimes.display(entry.last_appraisal?.toString()) ?: 'never',
            lastAppraisalIso: InvaryTimes.iso(entry.last_appraisal?.toString()),
        ]
    }

    /** The Morpheus view of an instance, on the Integrity tab, or null when the id is not one. */
    static String instanceUrl(String instanceId) {
        String id = instanceId?.trim()
        return id && id.isLong() ? "/provisioning/instances/${id}#!${InvaryInstanceTabProvider.CODE}-tab" : null
    }

    /** The Morpheus view of a server, on the Integrity tab, or null when the id is not one. */
    static String serverUrl(String serverId) {
        String id = serverId?.trim()
        return id && id.isLong() ? "/infrastructure/servers/${id}#!${InvaryServerTabProvider.CODE}-tab" : null
    }

    /** The Morpheus view of a server, as it opens by default, or null when the id is not one. */
    static String serverPageUrl(String serverId) {
        String id = serverId?.trim()
        return id && id.isLong() ? "/infrastructure/servers/${id}" : null
    }

    /** The Integrity tab of a server, or null when the id is not one. */
    static String serverIntegrityUrl(String serverId) {
        String url = serverPageUrl(serverId)
        return url ? "${url}#!${InvaryServerTabProvider.CODE}" : null
    }

    /** Reads a count the appraiser reported, which is zero when it reported none. */
    static int asCount(Object value) {
        return count(value)
    }

    private static int count(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0
    }
}
