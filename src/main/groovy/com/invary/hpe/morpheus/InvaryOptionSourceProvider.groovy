// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import com.morpheusdata.core.AbstractOptionSourceProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import groovy.util.logging.Slf4j

/**
 * Supplies the fixed choice lists behind the SELECT settings and task options.
 *
 * Morpheus option types have no inline list of choices. A select names an option source, and
 * for a plugin option source Morpheus invokes a method of that name on this provider, so each
 * list is exposed as a method named in {@link #getMethodNames()}.
 */
@Slf4j
class InvaryOptionSourceProvider extends AbstractOptionSourceProvider {

    /** Option source names, referenced by the optionSource of a SELECT option type. */
    static final String SOURCE_REMEDIATION_ACTIONS = 'invaryRemediationActions'
    static final String SOURCE_REMEDIATION_ACTIONS_INHERIT = 'invaryRemediationActionsWithInherit'
    static final String SOURCE_CERT_TRUST_MODES = 'invaryCertTrustModes'
    static final String SOURCE_CERT_TRUST_MODES_INHERIT = 'invaryCertTrustModesWithInherit'

    /** Offered by the task options, where an unset value defers to the plugin settings. */
    private static final Map INHERIT_CHOICE =
        [name: 'Inherit from plugin settings', value: InvarySensorConfig.INHERIT]

    private static final List<Map> REMEDIATION_CHOICES = [
        [name: 'None',    value: 'none'],
        [name: 'Stop',    value: 'stop'],
        [name: 'Restart', value: 'restart'],
        [name: 'Suspend', value: 'suspend'],
        [name: 'Revert',  value: 'revert'],
    ].asImmutable()

    private static final List<Map> CERT_TRUST_CHOICES = [
        [name: 'Auto (ask the appraiser)', value: InvarySensorConfig.TRUST_AUTO],
        [name: 'Yes',                      value: InvarySensorConfig.TRUST_YES],
        [name: 'No',                       value: InvarySensorConfig.TRUST_NO],
    ].asImmutable()

    protected MorpheusContext morpheusContext
    protected Plugin plugin

    InvaryOptionSourceProvider(Plugin plugin, MorpheusContext morpheusContext) {
        this.plugin = plugin
        this.morpheusContext = morpheusContext
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
        return 'invary-option-source'
    }

    @Override
    String getName() {
        return 'Invary Option Source'
    }

    @Override
    List<String> getMethodNames() {
        return [
            SOURCE_REMEDIATION_ACTIONS,
            SOURCE_REMEDIATION_ACTIONS_INHERIT,
            SOURCE_CERT_TRUST_MODES,
            SOURCE_CERT_TRUST_MODES_INHERIT,
        ]
    }

    // Each option source is a method named in getMethodNames(), invoked with the attributes
    // of the field being rendered, and returning the choices as name and value pairs.

    def invaryRemediationActions(args) {
        return REMEDIATION_CHOICES
    }

    def invaryRemediationActionsWithInherit(args) {
        return [INHERIT_CHOICE] + REMEDIATION_CHOICES
    }

    def invaryCertTrustModes(args) {
        return CERT_TRUST_CHOICES
    }

    def invaryCertTrustModesWithInherit(args) {
        return [INHERIT_CHOICE] + CERT_TRUST_CHOICES
    }
}
