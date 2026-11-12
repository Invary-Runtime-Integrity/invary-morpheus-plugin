// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import com.morpheusdata.model.NetworkProxy
import com.sun.net.httpserver.HttpServer
import org.apache.http.HttpHost
import org.apache.http.HttpVersion
import org.apache.http.message.BasicHttpRequest
import org.apache.http.message.BasicHttpResponse
import org.apache.http.protocol.BasicHttpContext
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

    def "a customer package is revalidated only once the copy held is a minute old"() {
        given:
        byte[] bytes = archive([(DEB): 'the deb repo package'.bytes])
        List<String> asked = Collections.synchronizedList([])
        HttpServer server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/pkg.tar.gz') { exchange ->
            String match = exchange.requestHeaders.getFirst('If-None-Match')
            asked << (match ?: 'unconditional')
            exchange.responseHeaders.add('ETag', '"v1"')
            if (match == '"v1"') {
                exchange.sendResponseHeaders(304, -1)
            } else {
                exchange.sendResponseHeaders(200, bytes.length)
                exchange.responseBody.write(bytes)
            }
            exchange.close()
        }
        server.start()
        String url = "http://127.0.0.1:${server.address.port}/pkg.tar.gz"

        when:
        InvaryCustomerPackage.repoPackage(url, 'deb')
        InvaryCustomerPackage.repoPackage(url, 'deb')

        then:
        // the second read is within the minute, so the origin is not asked
        asked == ['unconditional']

        when:
        InvaryCustomerPackage.CACHE[url].fetchedAt -= InvaryCustomerPackage.REVALIDATE_MILLIS
        byte[] revalidated = InvaryCustomerPackage.repoPackage(url, 'deb')
        InvaryCustomerPackage.repoPackage(url, 'deb')

        then:
        // a 304 starts the minute again
        asked == ['unconditional', '"v1"']
        new String(revalidated) == 'the deb repo package'

        cleanup:
        server.stop(0)
    }

    // -- The appliance proxy --------------------------------------------------

    static NetworkProxy proxy(Map fields = [:]) {
        NetworkProxy proxy = new NetworkProxy()
        ([proxyHost: 'proxy.example', proxyPort: 3128] + fields).each { name, value -> proxy[name] = value }
        return proxy
    }

    static final URI PACKAGE_URI = new URI('https://packages.example/pkg.tar.gz')

    def "a customer package is fetched through the appliance proxy"() {
        when:
        HttpHost via = InvaryCustomerPackage.proxyFor(proxy(), PACKAGE_URI)

        then:
        via.hostName == 'proxy.example'
        via.port == 3128
    }

    @Unroll
    def "a customer package is fetched directly when #described"() {
        expect:
        InvaryCustomerPackage.proxyFor(configured, PACKAGE_URI) == null

        where:
        described                               | configured
        'no proxy is configured'                | null
        'the proxy names no host'               | proxy(proxyHost: ' ')
        'its host is in the no-proxy list'      | proxy(noProxy: 'packages.example')
        'its host is listed in another case'    | proxy(noProxy: 'PACKAGES.example')
        'its host is listed after commas'       | proxy(noProxy: 'localhost,packages.example')
        'its host is listed after pipes'        | proxy(noProxy: 'localhost|packages.example')
        'its host is listed after whitespace'   | proxy(noProxy: 'localhost \n packages.example')
    }

    def "a no-proxy entry must name the host, not a part of it"() {
        expect:
        InvaryCustomerPackage.proxyFor(proxy(noProxy: 'example, .example, packages'), PACKAGE_URI) != null
    }

    // -- Limits ---------------------------------------------------------------

    /** A gzipped archive of one header, with no body behind it, and the end of the archive. */
    static byte[] headerOnly(byte[] header) {
        def bytes = new ByteArrayOutputStream()
        new GZIPOutputStream(bytes).withCloseable { gz ->
            gz.write(header)
            gz.write(new byte[BLOCK * 2])
        }

        return bytes.toByteArray()
    }

    def "a repo package larger than the limit is refused before it is read"() {
        given:
        // only the header claims the size, so nothing that large is ever built or read
        int size = (int) InvaryCustomerPackage.MAX_REPO_PKG_BYTES + 1

        when:
        extract(headerOnly(header(DEB, size, '0' as char)))

        then:
        def e = thrown(InvaryCustomerPackage.ArchiveException)
        e.message == "The customer package holds ${DEB} of ${size} bytes, more than the 8 MiB a repo package can be."
    }

    @Unroll
    def "a size field of #described is refused as not a tar archive"() {
        given:
        byte[] header = header(DEB, 0, '0' as char)
        Arrays.fill(header, 124, 136, (byte) 0)
        field.eachWithIndex { b, int i -> header[124 + i] = (byte) b }

        when:
        extract(headerOnly(header))

        then:
        def e = thrown(InvaryCustomerPackage.ArchiveException)
        e.message == 'The customer package is not a valid tar archive.'

        where:
        described     | field
        'a negative'  | '-0000000001'.bytes.toList()
        'a non octal' | '0000000009'.bytes.toList()
        'base-256'    | [0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10, 0]
    }

    def "an archive larger than the limit once decompressed is refused"() {
        given:
        // a member nothing wants is skipped, and still counts
        byte[] archive = archive(['invary-onprem/invary-appraiser.tar.gz': new byte[64 * 1024], (DEB): 'deb'.bytes])

        when:
        InvaryCustomerPackage.extract(new ByteArrayInputStream(archive), 16 * 1024,
            System.currentTimeMillis() + 60_000)

        then:
        def e = thrown(InvaryCustomerPackage.ArchiveException)
        e.message.endsWith('once decompressed.')
    }

    def "an archive within the limits is read whole"() {
        when:
        def found = InvaryCustomerPackage.extract(new ByteArrayInputStream(archive([(DEB): 'deb'.bytes])),
            64 * 1024, System.currentTimeMillis() + 60_000)

        then:
        new String(found.deb) == 'deb'
    }

    def "a download past its deadline is abandoned"() {
        when:
        InvaryCustomerPackage.extract(new ByteArrayInputStream(archive([(DEB): 'deb'.bytes])),
            InvaryCustomerPackage.MAX_ARCHIVE_BYTES, System.currentTimeMillis() - 1)

        then:
        def e = thrown(InvaryCustomerPackage.ArchiveException)
        e.message == 'The customer package did not download within 15 minutes.'
    }

    def "the limits are stated as #expected"() {
        expect:
        InvaryCustomerPackage.bytesText(bytes) == expected

        where:
        bytes                                    || expected
        InvaryCustomerPackage.MAX_REPO_PKG_BYTES || '8 MiB'
        InvaryCustomerPackage.MAX_ARCHIVE_BYTES  || '1 GiB'
    }

    // -- Redirects ------------------------------------------------------------

    static URI redirect(String from, String to) {
        def request = new BasicHttpRequest('GET', from)
        def response = new BasicHttpResponse(HttpVersion.HTTP_1_1, 302, 'Found')
        response.setHeader('Location', to)
        return new InvaryCustomerPackage.HttpsOnlyRedirectStrategy().getLocationURI(request, response, new BasicHttpContext())
    }

    def "a redirect that stays on https is followed"() {
        expect:
        redirect('https://packages.example/pkg.tar.gz', 'https://cdn.example/pkg.tar.gz') ==
            new URI('https://cdn.example/pkg.tar.gz')
    }

    @Unroll
    def "a redirect to #to is refused"() {
        when:
        redirect('https://packages.example/pkg.tar.gz', to)

        then:
        def e = thrown(InvaryCustomerPackage.InsecureRedirectException)
        e.location == new URI(to)

        where:
        to << ['http://cdn.example/pkg.tar.gz', 'ftp://cdn.example/pkg.tar.gz']
    }

    def "a refused redirect fails the fetch rather than being treated as transient"() {
        given:
        HttpServer server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/pkg.tar.gz') { exchange ->
            exchange.responseHeaders.add('Location', "http://127.0.0.1:${server.address.port}/elsewhere.tar.gz")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        String url = "http://127.0.0.1:${server.address.port}/pkg.tar.gz"

        // a cached copy would stand in for a transient failure, and must not for this one
        InvaryCustomerPackage.seedCache(url, [deb: 'cached'.bytes])
        InvaryCustomerPackage.CACHE[url].fetchedAt = 0

        when:
        InvaryCustomerPackage.repoPackage(url, 'deb')

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "The customer package at ${url} redirected to " +
                     "http://127.0.0.1:${server.address.port}/elsewhere.tar.gz, which is not an https URL."

        cleanup:
        server.stop(0)
    }
}
