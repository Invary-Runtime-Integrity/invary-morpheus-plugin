// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import spock.lang.Specification
import spock.lang.Unroll

import java.util.zip.GZIPOutputStream

/**
 * Reading the repo definition packages out of a customer package.
 *
 * The archives here are built rather than kept as fixtures: a real customer package runs to a
 * hundred megabytes, and what this needs to exercise is a few hundred bytes of tar structure.
 */
class InvaryCustomerPackageSpec extends Specification {

    static final int BLOCK = 512

    static final String DEB = InvaryCustomerPackage.memberPath('deb')
    static final String RPM = InvaryCustomerPackage.memberPath('rpm')

    def cleanup() {
        InvaryCustomerPackage.clearCache()
    }

    /**
     * A gzipped tar of the given entries, in order.
     *
     * Written by hand rather than with a library, for the same reason the reader is: tar is a
     * sequence of 512 byte headers, and pulling in a dependency to write eleven fields of one
     * would be the larger change.
     */
    static byte[] archive(Map<String, byte[]> entries, char type = '0' as char) {
        def bytes = new ByteArrayOutputStream()

        new GZIPOutputStream(bytes).withCloseable { gz ->
            entries.each { name, content ->
                gz.write(header(name, content.length, type))
                gz.write(content)

                int padding = (BLOCK - (content.length % BLOCK)) % BLOCK
                gz.write(new byte[padding])
            }

            // an archive ends in two zero blocks
            gz.write(new byte[BLOCK * 2])
        }

        return bytes.toByteArray()
    }

    static byte[] header(String name, int size, char type) {
        byte[] header = new byte[BLOCK]

        write(header, 0, name)
        write(header, 100, '0000644')                                   // mode
        write(header, 124, String.format('%011o', size))                // size, octal
        write(header, 136, String.format('%011o', 0))                   // modified
        header[156] = (byte) type
        write(header, 257, 'ustar')

        // the checksum is computed with its own field read as spaces, and nothing here verifies
        // it, but a well formed header carries one
        write(header, 148, '        ')
        int sum = 0
        header.each { sum += (it & 0xFF) }
        write(header, 148, String.format('%06o\0', sum))

        return header
    }

    private static void write(byte[] into, int at, String value) {
        byte[] bytes = value.getBytes('UTF-8')
        System.arraycopy(bytes, 0, into, at, bytes.length)
    }

    static Map<String, byte[]> extract(byte[] archive) {
        return InvaryCustomerPackage.extract(new ByteArrayInputStream(archive))
    }

    // -- What a customer package yields ---------------------------------------

    def "both repo packages are read out of a customer package"() {
        given:
        def bytes = archive([
            (DEB): 'the deb repo package'.bytes,
            (RPM): 'the rpm repo package'.bytes,
        ])

        when:
        def found = extract(bytes)

        then:
        found.keySet() == ['deb', 'rpm'] as Set
        new String(found['deb']) == 'the deb repo package'
        new String(found['rpm']) == 'the rpm repo package'
    }

    def "the members wanted are found among everything else a customer package carries"() {
        given:
        // the shape of a real one: the repo packages are small and late, behind the payloads
        def bytes = archive([
            'invary-onprem/invary-appraiser.tar.gz'        : new byte[4096],
            'invary-onprem/invary-sensor.tar.gz'           : new byte[8192],
            'invary-onprem/windows-sensor/InvarySensor.msi': new byte[1024],
            (RPM)                                          : 'rpm'.bytes,
            (DEB)                                          : 'deb'.bytes,
            'invary-onprem/README.md'                      : 'read me'.bytes,
        ])

        when:
        def found = extract(bytes)

        then:
        new String(found['deb']) == 'deb'
        new String(found['rpm']) == 'rpm'
    }

    def "a member whose size is not a whole number of blocks is read whole"() {
        given:
        // 513 bytes spans two blocks and leaves 511 of padding, which the next header follows
        byte[] content = new byte[513]
        content[0] = 'A'.bytes[0]
        content[512] = 'Z'.bytes[0]

        when:
        def found = extract(archive([(DEB): content, (RPM): 'rpm'.bytes]))

        then:
        found['deb'].length == 513
        found['deb'][0] == 'A'.bytes[0]
        found['deb'][512] == 'Z'.bytes[0]
        new String(found['rpm']) == 'rpm'
    }

    def "entries that are not regular files are skipped"() {
        given:
        // pax headers stand in front of entries in the format a real customer package uses
        def bytes = archive([(DEB): 'ignored'.bytes], 'x' as char)

        expect:
        extract(bytes).isEmpty()
    }

    def "an archive carrying neither member yields nothing"() {
        expect:
        extract(archive(['invary-onprem/README.md': 'read me'.bytes])).isEmpty()
    }

    // -- What is reported when it cannot be read ------------------------------

    def "a missing member is reported by the path it should have stood at"() {
        given:
        InvaryCustomerPackage.seedCache('https://invary/pkg.tar.gz', [deb: 'deb'.bytes])

        when:
        InvaryCustomerPackage.repoPackage('https://invary/pkg.tar.gz', 'rpm')

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(RPM)
        e.message.contains('https://invary/pkg.tar.gz')
    }

    @Unroll
    def "a package format of #fmt is refused before anything is fetched"() {
        when:
        InvaryCustomerPackage.repoPackage('https://invary/pkg.tar.gz', fmt)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(fmt)

        where:
        fmt << ['apk', 'none', '']
    }

    def "something that is not a gzip archive is reported as such"() {
        when:
        extract('this is not an archive, it is a web page'.bytes)

        then:
        thrown(java.util.zip.ZipException)
    }

    // -- The cache ------------------------------------------------------------

    def "a seeded customer package is served without being fetched"() {
        given:
        // the URL resolves to nothing, so anything reaching the network would fail rather than
        // quietly succeed
        InvaryCustomerPackage.seedCache('https://nowhere.invalid/pkg.tar.gz',
            [deb: 'deb'.bytes, rpm: 'rpm'.bytes])

        expect:
        new String(InvaryCustomerPackage.repoPackage('https://nowhere.invalid/pkg.tar.gz', 'deb')) == 'deb'
        new String(InvaryCustomerPackage.repoPackage('https://nowhere.invalid/pkg.tar.gz', 'rpm')) == 'rpm'
    }

    def "clearing the cache means the next read fetches again"() {
        given:
        InvaryCustomerPackage.seedCache('https://nowhere.invalid/pkg.tar.gz', [deb: 'deb'.bytes])
        InvaryCustomerPackage.clearCache()

        when:
        InvaryCustomerPackage.repoPackage('https://nowhere.invalid/pkg.tar.gz', 'deb')

        then:
        // nothing is cached, so it tries the origin and reports that it could not be reached
        def e = thrown(IllegalArgumentException)
        e.message.contains('https://nowhere.invalid/pkg.tar.gz')
    }

    def "one customer package is cached apart from another"() {
        given:
        InvaryCustomerPackage.seedCache('https://invary/next.tar.gz', [deb: 'next'.bytes])
        InvaryCustomerPackage.seedCache('https://invary/stable.tar.gz', [deb: 'stable'.bytes])

        expect:
        // a regenerated customer package is a new URL, which is a different key
        new String(InvaryCustomerPackage.repoPackage('https://invary/next.tar.gz', 'deb')) == 'next'
        new String(InvaryCustomerPackage.repoPackage('https://invary/stable.tar.gz', 'deb')) == 'stable'
    }
}
