// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

/**
 * Renders text that came from elsewhere, such as the body of an HTTP response, so it can be
 * written to the appliance log.
 */
class InvaryText {

    private static final Map<Character, String> LOG_ESCAPES = [
        ('\n' as char): '\\n',
        ('\r' as char): '\\r',
        ('\t' as char): '\\t',
    ].asImmutable()

    /**
     * Text fit for one log line: control characters are escaped, so the text cannot start a
     * line of its own that reads as another log entry, and text longer than `max` characters is
     * cut, saying how much was left out.
     *
     * @return the rendered text, or null for null
     */
    static String loggable(String text, int max) {
        if (text == null) {
            return null
        }

        boolean cut = text.length() > max
        String kept = cut ? text.substring(0, max) : text
        StringBuilder out = new StringBuilder()

        for (char c : kept.toCharArray()) {
            if (LOG_ESCAPES.containsKey(c)) {
                out.append(LOG_ESCAPES[c])
            } else if (Character.isISOControl(c)) {
                // built in two parts, so the source holds no backslash-u sequence for the
                // compiler to take as a Unicode escape
                out.append('\\').append('u').append(String.format('%04X', (int) c))
            } else {
                out.append(c)
            }
        }

        if (cut) {
            out.append("... (${text.length() - max} more characters)")
        }

        return out.toString()
    }
}
