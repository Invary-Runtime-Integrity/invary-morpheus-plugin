// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import groovy.util.logging.Slf4j

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Holds appraisal report times in a form a page can show. The appraiser
 * reports times to the nanosecond, as `2026-08-18T19:51:00.935816648+00:00`, which is more than
 * a reader of a report or of the Analytics page needs.
 *
 * Each time is rendered twice: once in UTC for the page as served, and once as an instant the
 * browser can restate in the timezone of whoever is reading it.
 */
@Slf4j
class InvaryTimes {

    /** Appraisal times are only shown to the second. */
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern('yyyy-MM-dd HH:mm:ss')

    /** @return the time in UTC to the second, or null when there is no usable time */
    static String display(String timestamp) {
        OffsetDateTime parsed = parse(timestamp)
        return parsed ? "${DISPLAY_TIME.format(parsed.withOffsetSameInstant(ZoneOffset.UTC))} UTC" : null
    }

    /** @return the current time, in the form {@link #display} returns */
    static String now() {
        return "${DISPLAY_TIME.format(OffsetDateTime.now(ZoneOffset.UTC))} UTC".toString()
    }

    /** @return the same time in a form the browser can localize, or null when there is none */
    static String iso(String timestamp) {
        return parse(timestamp)?.toInstant()?.toString()
    }

    private static OffsetDateTime parse(String timestamp) {
        if (!timestamp?.trim()) {
            return null
        }

        try {
            return OffsetDateTime.parse(timestamp.trim())
        } catch (Exception e) {
            log.warn("Could not read the appraisal time '${timestamp}': ${e.message}")
            return null
        }
    }
}
