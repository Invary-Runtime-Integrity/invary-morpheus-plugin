// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import groovy.util.logging.Slf4j
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients

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

    private static final int CONNECT_TIMEOUT_MILLIS = 30 * 1000
    private static final int READ_TIMEOUT_MILLIS = 5 * 60 * 1000

    /** A tar block, which is also the unit every header and every file body is padded to. */
    private static final int BLOCK = 512

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
     * @throws IllegalArgumentException naming the URL and what went wrong, which is what the
     *         task reports as its failure
     */
    static byte[] repoPackage(String url, String pkgFmt) {
        if (!FORMATS.contains(pkgFmt)) {
            throw new IllegalArgumentException(
                "The Invary Sensor cannot be installed with the '${pkgFmt}' package format. " +
                "A customer package carries ${FORMATS.join(' and ')} packages.")
        }

        byte[] bytes = packages(url)[pkgFmt]
        if (!bytes) {
            throw new IllegalArgumentException(
                "The customer package at ${url} contains no ${memberPath(pkgFmt)}. " +
                'Check that the URL points at an Invary customer package.')
        }

        return bytes
    }

    /** Empties the cache. Exists for tests, which must not see another test's fetch. */
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
     * A cached archive is never served without asking the origin about it first: the fetch is
     * conditional, so a customer package replaced at the same URL - which is how a re-hosted
     * archive moves a fleet from one channel to another - is noticed rather than cached over.
     * A conditional request that answers 304 costs a few hundred bytes, so a fleet rollout still
     * transfers the archive itself only once.
     */
    private static Map<String, byte[]> packages(String url) {
        synchronized (LOCKS.computeIfAbsent(url, { new Object() })) {
            Entry cached = CACHE[url]

            // an origin that offers neither validator cannot be asked whether anything changed,
            // so a cached archive is trusted for a while rather than re-fetched every task
            if (cached && !cached.etag && !cached.lastModified &&
                System.currentTimeMillis() - cached.fetchedAt < TTL_MILLIS) {
                log.debug("Using the cached Invary customer package from ${url}")
                return cached.packages
            }

            Entry fetched
            try {
                fetched = fetch(url, cached)
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

    /** Fetches the archive, or revalidates the copy already held. */
    private static Entry fetch(String url, Entry cached) {
        CloseableHttpClient client = HttpClients.custom()
            .setDefaultRequestConfig(RequestConfig.custom()
                .setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
                .setConnectionRequestTimeout(CONNECT_TIMEOUT_MILLIS)
                .setSocketTimeout(READ_TIMEOUT_MILLIS)
                .build())
            .build()

        try {
            HttpGet request = new HttpGet(url)
            if (cached?.etag) {
                request.setHeader('If-None-Match', cached.etag)
            }
            if (cached?.lastModified) {
                request.setHeader('If-Modified-Since', cached.lastModified)
            }

            log.info("Fetching the Invary customer package from ${url}")
            def response = client.execute(request)
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

                Map<String, byte[]> packages = extract(response.entity.content)
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
        Map<String, String> wanted = FORMATS.collectEntries { [(memberPath(it)): it] }
        Map<String, byte[]> found = [:]

        new GZIPInputStream(new BufferedInputStream(stream, 64 * 1024)).withCloseable { InputStream tar ->
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

    private static byte[] read(InputStream stream, long size) {
        byte[] bytes = new byte[(int) size]
        if (!readFully(stream, bytes, (int) size)) {
            throw new IllegalArgumentException('The customer package ended part way through a file.')
        }

        return bytes
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

    /** An octal number field of a tar header, which may be padded with spaces or NULs. */
    private static long octal(byte[] header, int at, int length) {
        String digits = string(header, at, length).trim()
        return digits ? Long.parseLong(digits, 8) : 0L
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
