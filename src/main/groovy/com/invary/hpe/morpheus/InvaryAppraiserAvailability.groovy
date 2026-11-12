// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers an appraiser that could not be reached, so that page views fail at once for a while
 * rather than each waiting out a timeout.
 *
 * Only reachability is held, keyed by the appraiser's base URL, and never anything the appraiser
 * reported. Pages consult it; the install task and the report always try, and what any caller
 * learns updates it.
 */
class InvaryAppraiserAvailability {

    /** How long page views fail at once after the appraiser could not be reached. */
    static final long WINDOW_MILLIS = 30 * 1000

    private static class Failure {
        long failedAt
        String reason
    }

    private static final ConcurrentHashMap<String, Failure> FAILURES = new ConcurrentHashMap<>()

    /** Records that the appraiser at a base URL could not be reached, and why. */
    static void failed(String baseUrl, String reason, long now = System.currentTimeMillis()) {
        FAILURES[baseUrl] = new Failure(failedAt: now, reason: reason)
    }

    /** Records that the appraiser at a base URL answered. */
    static void reached(String baseUrl) {
        FAILURES.remove(baseUrl)
    }

    /**
     * @return why the appraiser at a base URL is not to be asked now, or null when it may be
     */
    static String unavailable(String baseUrl, long now = System.currentTimeMillis()) {
        Failure failure = FAILURES[baseUrl]
        if (failure == null) {
            return null
        }

        long elapsed = now - failure.failedAt
        if (elapsed >= WINDOW_MILLIS) {
            FAILURES.remove(baseUrl, failure)
            return null
        }

        long ago = elapsed.intdiv(1000)
        long retry = (WINDOW_MILLIS - elapsed + 999).intdiv(1000)
        return "The Invary Appraiser at ${baseUrl} could not be reached ${ago} s ago " +
               "(${failure.reason ?: 'no reason was reported'}). Retrying in ${retry} s."
    }

    /** Forgets every failure, as the plugin is removed. */
    static void clear() {
        FAILURES.clear()
    }
}
