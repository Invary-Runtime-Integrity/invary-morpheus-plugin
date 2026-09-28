// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import com.morpheusdata.core.AbstractReportProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ReportResult
import com.morpheusdata.model.ReportResultRow
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.HandlebarsRenderer
import com.morpheusdata.views.Renderer
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

import java.time.Instant

@Slf4j
class InvaryCustomReportProvider extends AbstractReportProvider {

    protected MorpheusContext morpheusContext
    protected Plugin plugin

    private HandlebarsRenderer hbsRenderer

    InvaryCustomReportProvider(Plugin plugin, MorpheusContext morpheusContext) {
        this.morpheusContext = morpheusContext
        this.plugin = plugin
    }

    /**
     * The renderer of the report base class registers the asset and i18n helpers but not the nonce
     * helper, and the content security policy of the page carries 'strict-dynamic', which discards
     * both 'self' and 'unsafe-inline'. Any script that lacks the nonce of the request is therefore
     * never run, so the template needs {{nonce}} and the helper behind it.
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
        return this.morpheusContext
    }

    @Override
    Plugin getPlugin() {
        return this.plugin
    }

    @Override
    String getCode() {
        return 'invary-integrity-report'
    }

    @Override
    String getName() {
        return 'Invary Runtime Integrity'
    }

    @Override
    String getDescription() {
        return 'Displays a summary of Invary integrity appraisal results across managed servers.'
    }

    @Override
    String getCategory() {
        return 'inventory'
    }

    @Override
    Boolean getOwnerOnly() {
        return false
    }

    @Override
    Boolean getMasterOnly() {
        return false
    }

    @Override
    Boolean getSupportsAllZoneTypes() {
        return true
    }

    @Override
    List<OptionType> getOptionTypes() {
        return null
    }

    @Override
    ServiceResponse validateOptions(Map opts) {
        return ServiceResponse.success()
    }

    @Override
    void process(ReportResult reportResult) {
        morpheus.report.updateReportResultStatus(reportResult, ReportResult.Status.generating).blockingAwait()
        Long displayOrder = 0

        try {
            InvaryAppraiserClient client = InvarySettings.appraiserClient(morpheus, plugin)
            if (!client) {
                log.error('Cannot generate the Invary integrity report: the Appraiser API URL is not configured in the plugin settings')
                morpheus.report.updateReportResultStatus(reportResult, ReportResult.Status.failed).blockingAwait()
                return
            }

            Map fleet = client.fetchFleetReport() ?: [:]
            List servers = ((fleet.endpoints ?: []) as List).collect { entry -> serverRow(entry as Map) }

            int totalServers = asCount(fleet.total_endpoints)
            int passingCount = asCount(fleet.passing_endpoints)
            int failingCount = asCount(fleet.failing_endpoints)
            int offlineCount = asCount(fleet.offline_endpoints)

            String fleetStatus = InvaryFleetSummary.fleetStatus(totalServers, failingCount, offlineCount)
            String latest = servers.collect { it.lastAppraisalIso }.findAll { it }.max()

            // Summary row for the header section
            Map<String, Object> summaryData = [
                totalServers    : totalServers.toString(),
                passing         : passingCount.toString(),
                failing         : failingCount.toString(),
                offline         : offlineCount.toString(),
                fleetStatus     : fleetStatus,
                bannerClass     : InvaryFleetSummary.bannerClass(fleetStatus),
                lastAppraisal   : InvaryTimes.display(latest) ?: 'never',
                lastAppraisalIso: latest,
                generatedAt     : InvaryTimes.now(),
                generatedAtIso  : Instant.now().toString(),
            ]
            ReportResultRow summaryRow = new ReportResultRow(
                section: ReportResultRow.SECTION_HEADER,
                displayOrder: displayOrder++,
                dataMap: summaryData
            )
            morpheus.report.appendResultRows(reportResult, [summaryRow]).blockingGet()

            // Detail rows for the main section, one per appraised instance
            Observable.fromIterable(servers).map { entry ->
                return new ReportResultRow(
                    section: ReportResultRow.SECTION_MAIN,
                    displayOrder: displayOrder++,
                    dataMap: entry as Map<String, Object>
                )
            }.buffer(50).doOnComplete {
                morpheus.report.updateReportResultStatus(reportResult, ReportResult.Status.ready).blockingAwait()
            }.doOnError { Throwable t ->
                log.error("Error generating Invary report: ${t.message}", t)
                morpheus.report.updateReportResultStatus(reportResult, ReportResult.Status.failed).blockingAwait()
            }.subscribe { resultRows ->
                morpheus.report.appendResultRows(reportResult, resultRows).blockingGet()
            }
        } catch (Exception e) {
            log.error("Failed to generate Invary integrity report: ${e.message}", e)
            morpheus.report.updateReportResultStatus(reportResult, ReportResult.Status.failed).blockingAwait()
        }
    }

    /**
     * Converts one machine of the appraiser fleet report into a detail row. A machine that has
     * stopped uploading measurements is reported as offline rather than by the status of its
     * last appraisal, matching how the appraiser counts it.
     */
    private static Map<String, Object> serverRow(Map entry) {
        boolean offline = entry.offline == true
        String status = offline ? 'OFFLINE' : statusLabel(entry.status?.toString())
        String serverId = entry.server_id?.toString()
        String instanceId = entry.instance_id?.toString()

        return [
            serverId     : serverId,
            serverUrl    : InvaryFleetSummary.serverUrl(serverId),
            instanceUrl  : instanceUrl(instanceId),
            // a row leads to the machine it reports on, falling back to the instance it is part
            // of for a machine appraised before servers were tracked
            url          : InvaryFleetSummary.serverUrl(serverId) ?: instanceUrl(instanceId),
            os           : operatingSystem(entry),
            instanceId   : instanceId,
            server       : entry.name?.toString(),
            ip           : entry.ip_addr?.toString(),
            status       : status,
            rowClass     : status == 'FAILED' || status == 'ERROR' ? 'danger' : (status == 'PASSED' ? '' : 'warning'),
            labelClass   : status == 'PASSED' ? 'label-success' : (status == 'FAILED' || status == 'ERROR' ? 'label-danger' : 'label-warning'),
            checks       : asCount(entry.num_checks).toString(),
            failed       : asCount(entry.num_failures).toString(),
            kernel          : entry.kernel_ver?.toString(),
            sensor          : entry.sensor_ver?.toString(),
            lastAppraisal   : InvaryTimes.display(entry.last_appraisal?.toString()) ?: 'never',
            lastAppraisalIso: InvaryTimes.iso(entry.last_appraisal?.toString()),
        ] as Map<String, Object>
    }


    /**
     * Builds the link to the Morpheus view of an instance. The identifier comes from the
     * sensor's instance tag, which an operator can set by hand, so anything that is not
     * an instance identifier is reported without a link rather than as one that leads
     * nowhere.
     *
     * The fragment selects the Integrity tab of the instance.
     *
     * @return the link, or null when the identifier cannot name an instance
     */
    private static String instanceUrl(String instanceId) {
        String id = instanceId?.trim()
        return id && id.isLong() ? "/provisioning/instances/${id}#!${InvaryInstanceTabProvider.CODE}-tab" : null
    }

    /**
     * Describes the operating system of a machine as its kernel name, distribution and
     * distribution release, followed by the processor architecture.
     */
    private static String operatingSystem(Map entry) {
        String name = [entry.os, entry.os_distro, entry.os_release]
            .collect { it?.toString()?.trim() }
            .findAll { it }
            .join(' ')

        String arch = entry.arch?.toString()?.trim()
        return arch ? "${name ?: 'unknown'} (${arch})" : (name ?: 'unknown')
    }

    /**
     * Maps an appraisal status recorded by the appraiser onto the label the report shows
     * for it.
     */
    private static String statusLabel(String status) {
        switch (status) {
            case 'successful': return 'PASSED'
            case 'failed':     return 'FAILED'
            case 'errored':    return 'ERROR'
            default:           return 'UNKNOWN'
        }
    }

    private static int asCount(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0
    }

    @Override
    HTMLResponse renderTemplate(ReportResult reportResult, Map<String, List<ReportResultRow>> reportRowsBySection) {
        ViewModel<Map<String, List<ReportResultRow>>> model = new ViewModel<>()
        model.object = reportRowsBySection
        return getRenderer().renderTemplate("hbs/invaryReport", model)
    }
}