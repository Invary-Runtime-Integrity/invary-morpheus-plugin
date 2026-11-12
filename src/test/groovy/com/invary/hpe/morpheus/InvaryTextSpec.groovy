// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import spock.lang.Specification
import spock.lang.Unroll

class InvaryTextSpec extends Specification {

    @Unroll
    def "#described is escaped onto one line"() {
        expect:
        InvaryText.loggable(text, 100) == expected

        where:
        described                   | text                                    || expected
        'a newline'                 | 'a\nb'                                  || 'a\\nb'
        'a carriage return'         | 'a\rb'                                  || 'a\\rb'
        'a tab'                     | 'a\tb'                                  || 'a\\tb'
        'another control character' | 'a' + String.valueOf((char) 0x1b) + 'b' || 'a' + '\\' + 'u001Bb'
        'a delete'                  | 'a' + String.valueOf((char) 0x7f) + 'b' || 'a' + '\\' + 'u007Fb'
    }

    def "a forged log line stays part of the one it is in"() {
        expect:
        InvaryText.loggable('{"error":"x"}\r\n2026-01-01 ERROR forged', 100).readLines().size() == 1
    }

    def "text within the limit is unchanged"() {
        given:
        String text = '{"error":"license expired", "detail":"' + ('x' * 50) + '"}'

        expect:
        InvaryText.loggable(text, text.length()) == text
    }

    def "text over the limit is cut, saying how much was left out"() {
        expect:
        InvaryText.loggable('abcdefghij', 4) == 'abcd... (6 more characters)'
    }

    def "the limit counts characters before they are escaped"() {
        expect:
        InvaryText.loggable('a\nbcd', 2) == 'a\\n... (3 more characters)'
    }

    def "nothing is rendered as nothing"() {
        expect:
        InvaryText.loggable(null, 10) == null
        InvaryText.loggable('', 10) == ''
    }
}
