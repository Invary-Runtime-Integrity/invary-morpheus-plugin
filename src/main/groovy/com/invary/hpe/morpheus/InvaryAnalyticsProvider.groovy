// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.AbstractAnalyticsProvider
import com.morpheusdata.model.User
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.HandlebarsRenderer
import com.morpheusdata.views.Renderer
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

import java.time.Instant

/**
 * Reports the integrity of the fleet on the Morpheus Analytics page: how the appraised instances
 * divide into passing, failing and offline, and which of them failed.
 *
 * Analytics sections carry a display order rather than being owned by one plugin, so this sits
 * alongside the sections of Morpheus and of any other plugin. Whoever is allowed the Operations
 * Analytics section sees it; it asks for no permission of its own.
 */
@Slf4j
class InvaryAnalyticsProvider extends AbstractAnalyticsProvider {

    static final String CODE = 'invary-integrity-analytics'

    Plugin plugin
    MorpheusContext morpheusContext

    private HandlebarsRenderer hbsRenderer

    InvaryAnalyticsProvider(Plugin plugin, MorpheusContext context) {
        this.plugin = plugin
        this.morpheusContext = context
    }

    /**
     * The renderer of the analytics base class registers the asset and i18n helpers but not the
     * nonce helper, and the content security policy of the page carries 'strict-dynamic', which
     * discards both 'self' and 'unsafe-inline'. Script that lacks the nonce of the request is
     * therefore never run, so the template needs {{nonce}} and the helper behind it.
     */
    @Override
    Renderer<?> getRenderer() {
        if (hbsRenderer == null) {
            hbsRenderer = new HandlebarsRenderer('renderer', plugin.classLoader)
            hbsRenderer.registerAssetHelper(plugin.name)
            hbsRenderer.registerI18nHelper(plugin, morpheusContext)
            hbsRenderer.registerNonceHelper(morpheusContext.webRequest)
        }
        return hbsRenderer
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
    String getCode() {
        return CODE
    }

    @Override
    String getName() {
        return 'Runtime Integrity'
    }

    @Override
    String getCategory() {
        return 'invary'
    }

    @Override
    String getDescription() {
        return 'The integrity of the instances the Invary Appraiser has appraised'
    }

    @Override
    Boolean getMasterTenantOnly() {
        return false
    }

    @Override
    Boolean getSubTenantOnly() {
        return false
    }

    @Override
    Integer getDisplayOrder() {
        return 100
    }

    @Override
    ServiceResponse<Map<String, Object>> loadData(User user, Map<String, Object> opts) {
        Map summary = InvaryFleetSummary.load(morpheusContext, plugin)
        summary.failingServers = InvaryFleetSummary.failing(summary)
        summary.overflow = InvaryFleetSummary.overflow(summary)
        summary.offlineServers = InvaryFleetSummary.offline(summary)
        summary.offlineOverflow = InvaryFleetSummary.offlineOverflow(summary)

        String fleetStatus = InvaryFleetSummary.fleetStatus(summary)
        String latest = InvaryFleetSummary.latestAppraisal(summary)
        summary.fleetStatus = fleetStatus
        summary.bannerClass = InvaryFleetSummary.bannerClass(fleetStatus)
        summary.lastAppraisal = InvaryTimes.display(latest) ?: 'never'
        summary.lastAppraisalIso = latest
        summary.loadedAt = InvaryTimes.now()
        summary.loadedAtIso = Instant.now().toString()

        return ServiceResponse.success(summary as Map<String, Object>)
    }

    @Override
    HTMLResponse renderTemplate(User user, Map<String, Object> data, Map<String, Object> opts) {
        // the data of a section is loaded before it is drawn, though a section that is drawn
        // without it reads the appraiser itself rather than showing nothing
        Map summary = data ?: loadData(user, opts).data as Map

        ViewModel<Map> model = new ViewModel<>()
        model.object = summary
        model.opts = opts

        return getRenderer().renderTemplate('hbs/invaryAnalytics', model)
    }
}
