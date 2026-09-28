// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

/**
 * Puts one appraisal of one machine into the shape `hbs/invaryTab` renders it in.
 *
 * The server tab and the instance tab both show the detail of an appraisal, so they share this
 * rather than each mapping the appraiser's JSON themselves.
 */
class InvaryAppraisalView {

    /**
     * Maps an appraisal as the appraiser reports it.
     *
     * @param appraisal the appraisal report, as returned by the server or endpoint API
     * @return the values the detail template reads
     */
    static Map detail(Map appraisal) {
        List checks = (appraisal.checks ?: []).collect { check ->
            check + [resultLabel: label(check.result?.toString())]
        }

        return [
            id           : appraisal.id,
            endpoint     : appraisal.endpoint,
            created      : appraisal.created,
            status       : appraisal.status,
            statusLabel  : label(appraisal.status?.toString()),
            passed       : appraisal.passed ?: [],
            failed       : appraisal.failed ?: [],
            agent        : appraisal.agent ?: [:],
            measurement  : appraisal.measurement ?: [:],
            kernel       : appraisal.kernel ?: [:],
            node         : appraisal.node ?: [:],
            distribution : appraisal.distribution ?: [:],
            checks       : checks,
            info         : appraisal.info ?: [],
            network      : appraisal.network,
        ]
    }

    /** The Bootstrap label an appraisal status or check result is shown with. */
    private static String label(String result) {
        switch (result) {
            case 'SUCCESSFUL': return 'label-success'
            case 'FAILED':     return 'label-danger'
            default:           return 'label-default'
        }
    }
}
