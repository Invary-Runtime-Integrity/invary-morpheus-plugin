// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import com.morpheusdata.core.AbstractInstanceTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.InstanceTabProvider
import com.morpheusdata.model.Account
import com.morpheusdata.model.Instance
import com.morpheusdata.model.ContentSecurityPolicy
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

@Slf4j
class InvaryInstanceTabProvider extends AbstractInstanceTabProvider implements InstanceTabProvider {

    /**
     * Identifies this tab to Morpheus. The UI builds the anchor that selects the tab by
     * appending `-tab` to this code, so a link to the tab is
     * `/provisioning/instances/<id>#!invary-integrity-tab`.
     */
    static final String CODE = 'invary-integrity'

    protected MorpheusContext morpheusContext
    protected Plugin plugin

    InvaryInstanceTabProvider(Plugin plugin, MorpheusContext ctx) {
        this.plugin = plugin
        this.morpheusContext = ctx
    }

    @Override
    MorpheusContext getMorpheus() {
        return morpheusContext
    }

    @Override
    Plugin getPlugin() {
        return plugin
    }

    @Override
    String getCode() { return CODE }

    @Override
    String getName() { return 'Integrity' }

    /** Content Security Policy (allows VME Manager origin for API calls) **/
    @Override
    ContentSecurityPolicy getContentSecurityPolicy() {
        def csp = new ContentSecurityPolicy()
        return csp
    }

    @Override
    HTMLResponse renderTemplate(Instance instance) {
        ViewModel<Map> vm = new ViewModel<>()
        Map data = [instance: instance, tabTitle: 'Invary Info', error: null]

        try {
            InvaryAppraiserClient client = InvarySettings.appraiserClient(morpheus, plugin)
            if (!client) {
                data.error = 'Invary Appraiser API URL is not configured. Please set it in the plugin settings.'
                vm.object = data
                return getRenderer().renderTemplate('hbs/invaryTab', vm)
            }

            Map rollup = client.fetchInstanceReport(instance.id)
            if (!rollup) {
                data.error = 'No Invary appraisal is recorded for any vm under this instance. Run the Invary Sensor ' +
                             'Install task on this instance.'
                vm.object = data
                return getRenderer().renderTemplate('hbs/invaryTab', vm)
            }

            List servers = (rollup.servers ?: []).collect { entry -> server(entry as Map) }

            // an instance of one server has nothing to roll up, so the appraisal of that server
            // is shown in full rather than as a table of one row
            if (servers.size() == 1) {
                Map appraisal = appraisalOf(client, servers[0] as Map)
                if (appraisal) {
                    data.putAll(InvaryAppraisalView.detail(appraisal))
                    vm.object = data
                    return getRenderer().renderTemplate('hbs/invaryTab', vm)
                }
            }

            data.putAll([
                status      : rollup.status,
                statusLabel : InvaryFleetSummary.fleetStatus(
                    InvaryFleetSummary.asCount(rollup.total_servers),
                    InvaryFleetSummary.asCount(rollup.failing_servers),
                    InvaryFleetSummary.asCount(rollup.offline_servers)),
                total       : InvaryFleetSummary.asCount(rollup.total_servers),
                passing     : InvaryFleetSummary.asCount(rollup.passing_servers),
                failing     : InvaryFleetSummary.asCount(rollup.failing_servers),
                offline     : InvaryFleetSummary.asCount(rollup.offline_servers),
                lastAppraisal   : InvaryTimes.display(rollup.last_appraisal?.toString()) ?: 'never',
                lastAppraisalIso: InvaryTimes.iso(rollup.last_appraisal?.toString()),
                servers     : servers,
            ])

            data.bannerClass = InvaryFleetSummary.bannerClass(data.statusLabel as String)
            vm.object = data
            return getRenderer().renderTemplate('hbs/invaryInstanceTab', vm)
        } catch (Exception e) {
            log.error("Failed to fetch Invary appraisal data: ${e.message}", e)
            data.error = "Failed to retrieve appraisal data: ${e.message}"
        }

        vm.object = data
        return getRenderer().renderTemplate('hbs/invaryTab', vm)
    }

    /** One row of the roll up, as the table renders it. */
    private static Map server(Map entry) {
        String serverId = entry.server_id?.toString()

        return [
            serverId     : serverId,
            // the name leads to the machine itself, its status to the appraisal behind that status
            serverUrl    : InvaryFleetSummary.serverPageUrl(serverId),
            integrityUrl : InvaryFleetSummary.serverIntegrityUrl(serverId),
            endpointId   : entry.endpoint?.toString(),
            name         : entry.name?.toString(),
            ip           : entry.ip_addr?.toString(),
            status       : entry.offline == true ? 'offline' : entry.status?.toString(),
            offline      : entry.offline == true,
            rowClass     : entry.offline == true ? 'warning' : (entry.status == 'successful' ? '' : 'danger'),
            checks       : InvaryFleetSummary.asCount(entry.num_checks),
            failures     : InvaryFleetSummary.asCount(entry.num_failures),
            lastAppraisal   : InvaryTimes.display(entry.last_appraisal?.toString()) ?: 'never',
            lastAppraisalIso: InvaryTimes.iso(entry.last_appraisal?.toString()),
        ]
    }

    /**
     * Reads the appraisal of one server of the roll up. A machine tagged with its server is
     * fetched by that; one appraised before servers were tracked is reached by the endpoint it
     * reported under.
     */
    private static Map appraisalOf(InvaryAppraiserClient client, Map server) {
        if (server.serverId) {
            Map appraisal = client.fetchServerAppraisal(server.serverId.toString() as Long)
            if (appraisal) {
                return appraisal
            }
        }

        return server.endpointId ? client.fetchEndpointAppraisal(server.endpointId as String) : null
    }

    @Override
    Boolean show(Instance instance, User user, Account account) {
        return true
    }
}