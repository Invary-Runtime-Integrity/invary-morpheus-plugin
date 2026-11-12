// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import com.morpheusdata.model.NetworkProxy
import groovy.util.logging.Slf4j
import org.apache.http.HttpHost
import org.apache.http.HttpRequest
import org.apache.http.HttpResponse
import org.apache.http.ProtocolException
import org.apache.http.auth.AuthScope
import org.apache.http.auth.NTCredentials
import org.apache.http.client.ClientProtocolException
import org.apache.http.client.CredentialsProvider
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.DefaultRedirectStrategy
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.impl.client.HttpClients
import org.apache.http.impl.client.ProxyAuthenticationStrategy
import org.apache.http.protocol.HttpContext

import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * Reads the Invary repository definition package out of a customer package.
 *
 * A customer package is a tar.gz generated for one customer, holding the repo definition
 * packages that carry their repository credentials. It also holds the appraiser, the sensor,
 * the baseliner and the Windows installers, which is why it runs to around a hundred megabytes
 * for the few kilobytes this needs.
 *
 * The appliance fetches it rather than the node: a fleet rollout then transfers the archive
 * once instead of once per machine, and no managed node needs to reach the host serving it.
 * The archive is streamed rather than stored, so nothing of that size is ever held in memory or
 * written to disk - only the two repo packages are kept.
 */
@Slf4j
class InvaryCustomerPackage {

    /** The directory a customer package holds everything under. */
    static final String PACKAGE_DIR = 'invary-onprem'

    /**
     * The repo definition package, whose file name carries no channel: the same name holds the
     * release, next or staging package depending on which customer package it came from. Which
     * one it is can only be read from the package itself, which is why the install script
     * discovers the name rather than being told it.
     */
    static final String REPO_PACKAGE = 'invary-onprem-release-latest'

    /** The package formats a customer package carries a repo definition package for. */
    static final List<String> FORMATS = ['deb', 'rpm'].asImmutable()

    /** How long a cached archive is trusted when its origin offers nothing to revalidate with. */
    private static final long TTL_MILLIS = 30 * 60 * 1000

    /** How long a cached archive is served before its origin is asked whether it changed. */
    static final long REVALIDATE_MILLIS = 60 * 1000

    private static final int CONNECT_TIMEOUT_MILLIS = 30 * 1000
    private static final int READ_TIMEOUT_MILLIS = 5 * 60 * 1000

    /** A tar block, which is also the unit every header and every file body is padded to. */
    private static final int BLOCK = 512

    private static final long MIB = 1024L * 1024

    /**
     * The most a repo package may be. They are a few kilobytes. Only the repo packages are held
     * to it; every other member is skipped, and counts only towards {@link #MAX_ARCHIVE_BYTES}.
     */
    static final long MAX_REPO_PKG_BYTES = 8 * MIB

    /**
     * The most a customer package may decompress to, skipped members included, so a crafted
     * archive cannot keep the appliance reading. They run to around a hundred megabytes.
     */
    static final long MAX_ARCHIVE_BYTES = 1024 * MIB

    /**
     * How long a download may take as a whole. The read timeout bounds each read alone, which a
     * server sending a byte at a time never trips, and the fetch holds the lock every other task
     * for the same URL waits on.
     */
    static final long DOWNLOAD_DEADLINE_MILLIS = 15 * 60 * 1000

    private static final int MAX_REDIRECTS = 5

    /**
     * A customer package that cannot be read, for a reason that holds however often it is
     * fetched. The reason reads after "The customer package", so the fetch can name the URL.
     */
    static class ArchiveException extends IllegalArgumentException {
        final String reason

        ArchiveException(String reason) {
            super("The customer package ${reason}".toString())
            this.reason = reason
        }
    }

    /** A redirect to anything but https, which would carry the package over plain HTTP. */
    static class InsecureRedirectException extends ProtocolException {
        final URI location

        InsecureRedirectException(URI location) {
            super("Redirected to ${location}, which is not an https URL".toString())
            this.location = location
        }
    }

    /** Follows a redirect only when it keeps the download on https. */
    static class HttpsOnlyRedirectStrategy extends DefaultRedirectStrategy {
        @Override
        URI getLocationURI(HttpRequest request, HttpResponse response, HttpContext context) throws ProtocolException {
            URI location = super.getLocationURI(request, response, context)
            if (!'https'.equalsIgnoreCase(location.scheme)) {
                throw new InsecureRedirectException(location)
            }

            return location
        }
    }

    /**
     * Counts what is read through it and fails past a limit or a deadline, so neither a crafted
     * archive nor a slow server can hold the appliance.
     */
    private static class LimitedStream extends FilterInputStream {
        private final long limit
        private final long deadlineAt
        private long count

        LimitedStream(InputStream stream, long limit, long deadlineAt) {
            super(stream)
            this.limit = limit
            this.deadlineAt = deadlineAt
        }

        @Override
        int read() throws IOException {
            check()
            int b = super.read()
            if (b >= 0) {
                counted(1)
            }

            return b
        }

        @Override
        int read(byte[] into, int offset, int length) throws IOException {
            check()
            int n = super.read(into, offset, length)
            if (n > 0) {
                counted(n)
            }

            return n
        }

        @Override
        long skip(long n) throws IOException {
            check()
            long skipped = super.skip(n)
            counted(skipped)
            return skipped
        }

        private void check() {
            if (System.currentTimeMillis() > deadlineAt) {
                throw new ArchiveException(
                    "did not download within ${DOWNLOAD_DEADLINE_MILLIS.intdiv(60 * 1000)} minutes.")
            }
        }

        private void counted(long n) {
            count += n
            if (count > limit) {
                throw new ArchiveException("is larger than ${bytesText(limit)} once decompressed.")
            }
        }
    }

    /** What one fetch of a customer package yielded, and what it can be revalidated with. */
    private static class Entry {
        Map<String, byte[]> packages
        String etag
        String lastModified
        long fetchedAt
    }

    private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<>()

    /**
     * One lock per URL, so that a fleet rollout running in parallel fetches the archive once
     * rather than once per task.
     */
    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>()

    /** The path a repo definition package stands at inside a customer package. */
    static String memberPath(String pkgFmt) {
        return "${PACKAGE_DIR}/${REPO_PACKAGE}.${pkgFmt}".toString()
    }

    /**
     * The repo definition package for a package format, from the customer package at a URL.
     *
     * @param proxy the appliance's global proxy, or null to connect directly
     * @throws IllegalArgumentException naming the URL and what went wrong, which is what the
     *         task reports as its failure
     */
    static byte[] repoPackage(String url, String pkgFmt, NetworkProxy proxy = null) {
        if (!FORMATS.contains(pkgFmt)) {
            throw new IllegalArgumentException(
                "The Invary Sensor cannot be installed with the '${pkgFmt}' package format. " +
                "A customer package carries ${FORMATS.join(' and ')} packages.")
        }

        byte[] bytes = packages(url, proxy)[pkgFmt]
        if (!bytes) {
            throw new IllegalArgumentException(
                "The customer package at ${url} contains no ${memberPath(pkgFmt)}. " +
                'Check that the URL points at an Invary customer package.')
        }

        return bytes
    }

    /** Empties the cache, as the plugin is removed, and between tests. */
    static void clearCache() {
        CACHE.clear()
        LOCKS.clear()
    }

    /**
     * Seeds the cache, so that a caller is served without anything being fetched.
     *
     * Exists for tests. An entry seeded here carries no validator, so it is served for as long
     * as one fetched from an origin that offers none would be.
     */
    static void seedCache(String url, Map<String, byte[]> packages) {
        CACHE[url] = new Entry(packages: packages, fetchedAt: System.currentTimeMillis())
    }

    /**
     * The repo packages a customer package holds, fetched or revalidated.
     *
     * A cached archive is served without asking the origin about it only for a minute after it
     * was fetched or last revalidated: past that the fetch is conditional, so a customer package
     * replaced at the same URL - which is how a re-hosted archive moves a fleet from one channel
     * to another - is noticed rather than cached over. A conditional request that answers 304
     * costs a few hundred bytes, so a fleet rollout still transfers the archive itself only once,
     * and the minute spares the tasks of a rollout asking the origin one after another.
     */
    private static Map<String, byte[]> packages(String url, NetworkProxy proxy) {
        synchronized (LOCKS.computeIfAbsent(url, { new Object() })) {
            Entry cached = CACHE[url]

            // an origin that offers neither validator cannot be asked whether anything changed,
            // so a cached archive is trusted for a while rather than re-fetched every task
            long fresh = (cached?.etag || cached?.lastModified) ? REVALIDATE_MILLIS : TTL_MILLIS
            if (cached && System.currentTimeMillis() - cached.fetchedAt < fresh) {
                log.debug("Using the cached Invary customer package from ${url}")
                return cached.packages
            }

            Entry fetched
            try {
                fetched = fetch(url, cached, proxy)
            } catch (IllegalArgumentException e) {
                throw e
            } catch (Exception e) {
                // a transient failure must not fail a fleet rollout when the archive is already
                // in hand; a first fetch has nothing to fall back on and reports the cause
                if (cached) {
                    log.warn("Could not revalidate the Invary customer package at ${url}, using the " +
                             "copy already fetched: ${e.message}")
                    return cached.packages
                }

                throw new IllegalArgumentException(describe(e, url), e)
            }

            CACHE[url] = fetched
            return fetched.packages
        }
    }

    /**
     * The proxy a request to a URL goes through, read the way HttpApiClient reads the appliance's
     * setting so that the appraiser and the customer package are reached alike: a host named in
     * the no-proxy list, which is separated by commas, pipes or whitespace, is reached directly.
     *
     * @return the proxy, or null to connect directly
     */
    static HttpHost proxyFor(NetworkProxy proxy, URI target) {
        if (!proxy?.proxyHost?.trim()) {
            return null
        }

        String host = target?.host
        boolean bypassed = (proxy.noProxy ?: '').split(/[,|\s]+/).any { it && it.equalsIgnoreCase(host) }
        if (bypassed) {
            return null
        }

        return new HttpHost(proxy.proxyHost.trim(), proxy.proxyPort ?: -1)
    }

    /**
     * Fetches the archive, or revalidates the copy already held.
     *
     * @throws IllegalArgumentException for a failure fetching again would not change: a status,
     *         an insecure redirect, or an archive that breaks a limit or is not a tar archive
     */
    private static Entry fetch(String url, Entry cached, NetworkProxy proxy) {
        HttpClientBuilder builder = HttpClients.custom()
            .setDefaultRequestConfig(RequestConfig.custom()
                .setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
                .setConnectionRequestTimeout(CONNECT_TIMEOUT_MILLIS)
                .setSocketTimeout(READ_TIMEOUT_MILLIS)
                .setMaxRedirects(MAX_REDIRECTS)
                .build())
            .setRedirectStrategy(new HttpsOnlyRedirectStrategy())

        HttpHost via = proxyFor(proxy, new URI(url))
        if (via) {
            log.info("Fetching the Invary customer package through the proxy at ${via.hostName}:${via.port}")
            builder.setProxy(via)

            if (proxy.proxyUser) {
                CredentialsProvider credentials = new BasicCredentialsProvider()
                credentials.setCredentials(new AuthScope(via.hostName, via.port),
                    new NTCredentials(proxy.proxyUser, proxy.proxyPassword, proxy.proxyWorkstation, proxy.proxyDomain))
                builder.setDefaultCredentialsProvider(credentials)
                    .setProxyAuthenticationStrategy(new ProxyAuthenticationStrategy())
            }
        }

        CloseableHttpClient client = builder.build()

        try {
            HttpGet request = new HttpGet(url)
            if (cached?.etag) {
                request.setHeader('If-None-Match', cached.etag)
            }
            if (cached?.lastModified) {
                request.setHeader('If-Modified-Since', cached.lastModified)
            }

            log.info("Fetching the Invary customer package from ${url}")
            def response
            try {
                response = client.execute(request)
            } catch (ClientProtocolException e) {
                // the redirect strategy's refusal arrives wrapped, and must not be mistaken for a
                // transient failure a cached copy could stand in for
                if (e.cause instanceof InsecureRedirectException) {
                    throw new IllegalArgumentException("The customer package at ${url} redirected to " +
                        "${(e.cause as InsecureRedirectException).location}, which is not an https URL.", e)
                }

                throw e
            }

            long deadlineAt = System.currentTimeMillis() + DOWNLOAD_DEADLINE_MILLIS
            try {
                int status = response.statusLine.statusCode

                if (status == 304 && cached) {
                    log.info("The Invary customer package at ${url} is unchanged; using the copy already fetched")
                    return new Entry(
                        packages: cached.packages,
                        etag: cached.etag,
                        lastModified: cached.lastModified,
                        fetchedAt: System.currentTimeMillis(),
                    )
                }

                if (status != 200) {
                    throw new IllegalArgumentException(describeStatus(status, url))
                }

                Map<String, byte[]> packages
                try {
                    packages = extract(response.entity.content, MAX_ARCHIVE_BYTES, deadlineAt)
                } catch (ArchiveException e) {
                    throw new IllegalArgumentException("The customer package at ${url} ${e.reason}", e)
                }
                log.info("Read ${packages.keySet().sort().join(' and ')} repo packages from the customer package at ${url}")

                return new Entry(
                    packages: packages,
                    etag: response.getFirstHeader('ETag')?.value,
                    lastModified: response.getFirstHeader('Last-Modified')?.value,
                    fetchedAt: System.currentTimeMillis(),
                )
            } finally {
                response.close()
            }
        } finally {
            client.close()
        }
    }

    /**
     * Reads the repo definition packages out of a customer package stream, keyed by format.
     *
     * The stream is read rather than stored: the members wanted are a few kilobytes inside an
     * archive of around a hundred megabytes, and everything else is skipped without being kept.
     */
    static Map<String, byte[]> extract(InputStream stream) {
        return extract(stream, MAX_ARCHIVE_BYTES, System.currentTimeMillis() + DOWNLOAD_DEADLINE_MILLIS)
    }

    /**
     * {@link #extract(InputStream)} within a limit on the decompressed bytes read and a time to
     * have finished by.
     *
     * @throws ArchiveException if the archive breaks a limit or is not a tar archive
     */
    static Map<String, byte[]> extract(InputStream stream, long maxArchiveBytes, long deadlineAt) {
        Map<String, String> wanted = FORMATS.collectEntries { [(memberPath(it)): it] }
        Map<String, byte[]> found = [:]

        InputStream gzip = new GZIPInputStream(new BufferedInputStream(stream, 64 * 1024))
        new LimitedStream(gzip, maxArchiveBytes, deadlineAt).withCloseable { InputStream tar ->
            byte[] header = new byte[BLOCK]

            while (found.size() < wanted.size()) {
                if (!readFully(tar, header, BLOCK)) {
                    break
                }

                // the archive ends in zero blocks, and a name is the first thing a header holds
                if (header[0] == 0 as byte) {
                    break
                }

                String name = string(header, 0, 100)
                long size = octal(header, 124, 12)
                char type = (char) (header[156] & 0xFF)

                // regular files alone. Everything else - pax headers, directories, links - is
                // skipped, and the names wanted here are short enough to stand in the header
                if ((type == '0' as char || type == '\0' as char) && wanted.containsKey(name)) {
                    // checked before anything is allocated for it
                    if (size > MAX_REPO_PKG_BYTES) {
                        throw new ArchiveException("holds ${name} of ${size} bytes, more than the " +
                            "${bytesText(MAX_REPO_PKG_BYTES)} a repo package can be.")
                    }

                    found[wanted[name]] = read(tar, size)
                } else {
                    skip(tar, size)
                }

                skip(tar, (BLOCK - (size % BLOCK)) % BLOCK)
            }
        }

        return found
    }

    /** Reads exactly `count` bytes, or reports that the stream ended first. */
    private static boolean readFully(InputStream stream, byte[] into, int count) {
        int read = 0
        while (read < count) {
            int n = stream.read(into, read, count - read)
            if (n < 0) {
                return false
            }
            read += n
        }

        return true
    }

    /** Reads a repo package's body, which the caller has held to {@link #MAX_REPO_PKG_BYTES}. */
    private static byte[] read(InputStream stream, long size) {
        byte[] bytes = new byte[(int) size]
        if (!readFully(stream, bytes, (int) size)) {
            throw new ArchiveException('ended part way through a file.')
        }

        return bytes
    }

    /** A byte count as the limits are stated, in whole MiB or GiB. */
    static String bytesText(long bytes) {
        return bytes >= 1024 * MIB && bytes % (1024 * MIB) == 0
            ? "${bytes.intdiv(1024 * MIB)} GiB".toString()
            : "${bytes.intdiv(MIB)} MiB".toString()
    }

    /** Discards `count` bytes. Read rather than skipped, which a compressed stream may short. */
    private static void skip(InputStream stream, long count) {
        byte[] scratch = new byte[8192]
        long left = count

        while (left > 0) {
            int n = stream.read(scratch, 0, (int) Math.min(left, scratch.length as long))
            if (n < 0) {
                return
            }
            left -= n
        }
    }

    /** A NUL terminated field of a tar header. */
    private static String string(byte[] header, int at, int length) {
        int end = at
        while (end < at + length && header[end] != 0 as byte) {
            end++
        }

        return new String(header, at, end - at, 'UTF-8')
    }

    /**
     * An octal number field of a tar header, which may be padded with spaces or NULs.
     *
     * Octal digits alone: a sign would make the size negative, and the base-256 form GNU tar
     * uses for very large files is not something a customer package holds.
     */
    private static long octal(byte[] header, int at, int length) {
        String digits = string(header, at, length).trim()
        if (!digits) {
            return 0L
        }

        if (!(digits ==~ /[0-7]+/)) {
            throw new ArchiveException('is not a valid tar archive.')
        }

        return Long.parseLong(digits, 8)
    }

    private static String describeStatus(int status, String url) {
        switch (status) {
            case 404:
            case 410:
                return "There is no customer package at ${url} (HTTP ${status}). Check the URL, which " +
                       'Invary issues along with the package.'
            case 401:
            case 403:
                return "The customer package at ${url} was refused (HTTP ${status}). The URL may have " +
                       'expired, or may need credentials this plugin cannot supply.'
            default:
                return "The customer package at ${url} could not be downloaded (HTTP ${status})."
        }
    }

    /** What to report for a failure that is not an HTTP status. */
    private static String describe(Exception e, String url) {
        if (e instanceof UnknownHostException) {
            return "The host serving the customer package at ${url} could not be resolved."
        }

        if (e instanceof java.net.SocketTimeoutException ||
            e instanceof org.apache.http.conn.ConnectTimeoutException) {
            return "The customer package at ${url} did not download before the request timed out."
        }

        if (e instanceof java.util.zip.ZipException) {
            return "What was served at ${url} is not a gzip archive. Check that the URL points at an " +
                   'Invary customer package rather than at a page describing one.'
        }

        return "The customer package at ${url} could not be read: ${e.message}"
    }
}
