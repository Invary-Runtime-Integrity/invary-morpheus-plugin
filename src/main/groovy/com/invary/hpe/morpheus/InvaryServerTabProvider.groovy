// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import com.morpheusdata.core.AbstractServerTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.ServerTabProvider
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ContentSecurityPolicy
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

/**
 * Shows the integrity of one machine on its Morpheus server view.
 *
 * A sensor measures a machine, and a Morpheus server is a machine, so this is where an appraisal
 * belongs. The instance tab rolls these up over the servers that make up a service.
 */
@Slf4j
class InvaryServerTabProvider extends AbstractServerTabProvider implements ServerTabProvider {

    /**
     * Identifies this tab to Morpheus. The UI builds the anchor that selects the tab by
     * appending `-tab` to this code, so a link to the tab is
     * `/infrastructure/servers/<id>#!invary-server-integrity-tab`.
     */
    static final String CODE = 'invary-server-integrity'

    protected MorpheusContext morpheusContext
    protected Plugin plugin

    InvaryServerTabProvider(Plugin plugin, MorpheusContext ctx) {
        this.plugin = plugin
        this.morpheusContext = ctx
    }

    // -- Tab Metadata ---------------------------------------------------------

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

    @Override
    ContentSecurityPolicy getContentSecurityPolicy() {
        return new ContentSecurityPolicy()
    }

    // -- Tab Rendering --------------------------------------------------------

    @Override
    HTMLResponse renderTemplate(ComputeServer server) {
        ViewModel<Map> vm = new ViewModel<>()
        Map data = [server: server, tabTitle: 'Invary Info', error: null]

        try {
            InvaryAppraiserClient client = InvarySettings.appraiserClient(morpheus, plugin)
            if (!client) {
                data.error = 'Invary Appraiser API URL is not configured. Please set it in the plugin settings.'
                vm.object = data
                return getRenderer().renderTemplate('hbs/invaryTab', vm)
            }

            Map appraisal = client.fetchServerAppraisal(server.id)
            if (appraisal) {
                data.putAll(InvaryAppraisalView.detail(appraisal))
            } else {
                // a sensor installed before servers were tracked reports only its instance, so
                // it is appraised without this server being named in the measurement
                data.error = 'No Invary appraisal is recorded for this server. Run the Invary Sensor - Install task on it, ' +
                             'or re-run it if a sensor is already installed, so the sensor is tagged with this server.'
            }
        } catch (Exception e) {
            log.error("Failed to fetch Invary appraisal data: ${e.message}", e)
            data.error = "Failed to retrieve appraisal data: ${e.message}"
        }

        vm.object = data
        return getRenderer().renderTemplate('hbs/invaryTab', vm)
    }

    @Override
    Boolean show(ComputeServer server, User user, Account account) {
        return true
    }
}
