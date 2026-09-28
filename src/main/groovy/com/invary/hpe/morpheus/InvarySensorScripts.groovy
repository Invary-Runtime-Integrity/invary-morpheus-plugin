// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Invary

package com.invary.hpe.morpheus

import groovy.transform.ToString
import groovy.util.logging.Slf4j

import java.util.regex.Pattern


/**
 * Generates the commands run on a server to install and configure the Invary Sensor.
 *
 * Everything here is pure string building with no Morpheus types, so the generated scripts and
 * the configuration merge can be tested without a Morpheus appliance.
 */
@Slf4j
class InvarySensorScripts {

    // -- Paths and names on the target server ------------------------------------
    static final String LINUX_CONFIG = '/opt/invary/conf/invary.toml'
    static final String LINUX_BINARY = '/opt/invary/bin/invary'
    static final String LINUX_SERVICE = 'invary'

    /**
     * Where the installed sensor writes its log and its stdout.
     *
     * The installer starts the sensor with its log going to a file in here rather than to
     * stderr, and the sensor stops logging to stderr entirely once it has a log file, so the
     * journal holds nothing but the unit's own lifecycle: whatever stopped the sensor from
     * starting is only ever written here.
     */
    static final String LINUX_VAR_DIR = '/var/opt/invary'

    // -- Package repository ----------------------------------------------------

    /** The name of the sensor package, which is the same in both formats. */
    static final String SENSOR_PACKAGE = 'invary'

    /** Matches every Invary repo package, whichever channel and product line it configures. */
    static final String REPO_PACKAGE_GLOB = 'invary-*-release*'

    static final String PACKAGES_HOST_NAME = 'packages.invary.com'

    /** Where the repo package puts the signing key, and what dnf's gpgkey= points at. */
    static final String RPM_KEY = '/etc/pki/rpm-gpg/RPM-GPG-KEY-invary'

    /**
     * The last 8 digits of the signing key fingerprint
     * (4800D0EC7157E4481ACC5928B77F65FBA62871D5), lowercased, which is how rpm names an
     * imported key: gpg-pubkey-<short id>-<timestamp>.
     */
    static final String RPM_KEY_SHORT_ID = 'a62871d5'

    // -- Failure reporting ----------------------------------------------------

    /**
     * The line a failed run ends with, naming the exit code and the step that produced it.
     * The transcript is all there is to diagnose a remote install from, and the exit code on
     * its own does not say which command produced it.
     */
    static final String FAILURE_MARKER = 'invary-install-failed:'

    /**
     * Introduces the few lines that say why a run failed, which are written last, immediately
     * before the failure marker.
     *
     * The whole transcript is too long to be shown as a message: Morpheus elides the middle of
     * one, which is where diagnostics of any length end up. So the cause is summarized where
     * the elision cannot reach it, and that summary is what is reported as the failure.
     */
    static final String SUMMARY_MARKER = 'invary-install-summary:'

    /**
     * Introduces the one line a run writes when it moves the machine to another package channel,
     * as `old -> new`.
     *
     * A repository swap decides where every later sensor upgrade on that machine comes from, so
     * it has to be visible in the task history rather than only in the transcript. This marker is
     * what carries it up into the reported message.
     */
    static final String CHANNEL_MARKER = 'invary-install-channel:'

    /** Matches the channel marker, capturing the move it names. */
    private static final Pattern CHANNEL =
        ~/${Pattern.quote(CHANNEL_MARKER)}\s*([A-Za-z0-9_.\-]+\s*->\s*[A-Za-z0-9_.\-]+)/

    /**
     * Matches the failure marker, capturing the exit code and the step. The step runs to the
     * end of the line, except for the punctuation of a rendered map, since a transport may
     * report the transcript as process state rather than as plain lines.
     */
    private static final Pattern FAILURE =
        ~/${Pattern.quote(FAILURE_MARKER)}\s*exit=(\S+)\s+step=([^\r\n\]]*)/

    /** How much of the service journal, and of each sensor log, a failure reports. */
    private static final int JOURNAL_LINES = 50

    /** How much of the configuration a failure reports, which is all of any real one. */
    private static final int CONFIG_LINES = 200

    /** How many logged lines the summary of a cause carries, which has to stay short. */
    private static final int CAUSE_LINES = 12

    /** What a logged line has to hold to be one that explains a failure. */
    static final String CAUSE_PATTERN =
        'error|warn|panic|fatal|fail|denied|refused|killed|out of memory'

    /**
     * How long a started service is watched before it is called started.
     *
     * A sensor that is killed and restarted is running whenever it is sampled, so being up is
     * not what says an install succeeded - staying up is.
     */
    private static final int WATCH_SECONDS = 10

    /** How long a stopped sensor is given to go away before the install gives up on it. */
    private static final int STOP_SECONDS = 30

    /** What a failed run reported failing at, or null when the transcript names no step. */
    static Failure parseFailure(String output) {
        if (!output) {
            return null
        }

        def matcher = FAILURE.matcher(output)
        return matcher.find()
            ? new Failure(exitCode: matcher.group(1), step: matcher.group(2)?.trim() ?: null)
            : null
    }

    /**
     * The few lines a failed run summarized its cause in, or null when it reported none.
     *
     * The summary runs from its marker to the failure marker that ends the run, and is short
     * enough to be reported whole.
     */
    static String parseSummary(String output) {
        if (!output) {
            return null
        }

        int start = output.indexOf(SUMMARY_MARKER)
        if (start < 0) {
            return null
        }

        start += SUMMARY_MARKER.length()
        int end = output.indexOf(FAILURE_MARKER, start)
        String summary = (end < 0 ? output.substring(start) : output.substring(start, end)).trim()

        return summary ?: null
    }

    /**
     * The package channel move a run reported, as `old -> new`, or null when it moved the
     * machine between no channels.
     *
     * A run that installs onto the channel the machine is already on reports nothing here, so a
     * value means the repository really was replaced.
     */
    static String parseChannelChange(String output) {
        if (!output) {
            return null
        }

        def matcher = CHANNEL.matcher(output)
        return matcher.find() ? matcher.group(1).replaceAll(/\s+/, ' ') : null
    }

    /** What a failed run reported about itself. */
    @ToString(includeNames = true, includePackage = false)
    static class Failure {
        /** The exit code of the command that failed. */
        String exitCode

        /** What the run was doing when it failed, as the script named the step. */
        String step
    }

    // -- Managed configuration keys --------------------------------------------

    /**
     * The configuration the plugin owns: the key as the sensor reads it, and where the key is
     * written when a configuration does not already hold it. Every other setting in a
     * configuration belongs to the operator and is kept.
     *
     * A dotted path and the table it names are the same key to the sensor, so `[connect]` with
     * `allow.untrusted`, `[connect.allow]` with `untrusted`, and a top level
     * `connect.allow.untrusted` are all one key and all have to be recognized.
     */
    private static final List<Map> MANAGED = [
        [key: 'tags', table: '', name: 'tags'],
        [key: 'connect.uri', table: 'connect', name: 'uri'],
        [key: 'connect.allow.untrusted', table: 'connect', name: 'allow.untrusted'],
        [key: 'measurement.interval', table: 'measurement', name: 'interval'],
    ].collect { it.asImmutable() }.asImmutable()

    /** The configuration keys the plugin owns, as the sensor reads them. */
    static final List<String> MANAGED_KEYS = MANAGED.collect { it['key'] as String }.asImmutable()

    /**
     * A comment removed from a configuration wherever it is found, so that merging converges on
     * a file of settings alone.
     */
    private static final String REMOVED_COMMENT =
        '# Managed by the Invary Morpheus plugin - edits to these keys will be overwritten'

    /** A `key = value` assignment, capturing the key. Keys may be bare, quoted, or dotted. */
    private static final Pattern ASSIGNMENT = ~/^\s*((?:[A-Za-z0-9_.\-]+|"[^"]*"|'[^']*')(?:\s*\.\s*(?:[A-Za-z0-9_.\-]+|"[^"]*"|'[^']*'))*)\s*=(.*)$/

    /** A `[table]` or `[[array of tables]]` header, capturing the name. */
    private static final Pattern TABLE = ~/^\s*\[\[?\s*([^\]]+?)\s*\]\]?\s*$/

    // -- Probes ----------------------------------------------------------------

    /**
     * Separates the values of a reported list.
     *
     * A probe value is read out of the output wherever it appears rather than by line, so it
     * cannot contain whitespace, a comma or a bracket. Neither a package name nor a repository
     * file path can contain a plus.
     */
    static final String LIST_SEPARATOR = '+'

    /**
     * Reports what installing the sensor on this server has to take account of: the
     * architecture, whether a sensor is already installed, the current configuration file,
     * which package manager the server uses, whether the sensor was installed by the shell
     * installer, and which Invary packages and repositories are already present.
     *
     * The configuration is base64 encoded so that any content survives the round trip
     * unchanged.
     */
    static String linuxProbe() {
        return """\
# by which package manager is present rather than by /etc/os-release, which derivatives and
# containers misreport often enough to matter. The manager is asked for rather than dpkg or
# rpm themselves, because a Debian host may carry rpm without being an rpm host
if command -v dnf >/dev/null 2>&1; then
    PKGTOOL=dnf; PKGFMT=rpm
elif command -v yum >/dev/null 2>&1; then
    PKGTOOL=yum; PKGFMT=rpm
elif command -v apt-get >/dev/null 2>&1; then
    PKGTOOL=apt-get; PKGFMT=deb
else
    PKGTOOL=none; PKGFMT=none
fi

# the markers preinstall.sh refuses to install over, tested exactly as it tests them: a
# regular file at either path is the shell installer's, since neither the package nor
# systemctl enable ever creates one
SHELLINSTALL=no
for marker in /etc/systemd/system/${LINUX_SERVICE}.service /etc/init.d/${LINUX_SERVICE}; do
    if [ -f "\$marker" ] && [ ! -L "\$marker" ]; then
        SHELLINSTALL=yes
    fi
done

PKGVERSION=none
RELEASE=""
if [ "\$PKGFMT" = deb ]; then
    PKGVERSION=\$(dpkg-query -W -f='\${Version}' ${SENSOR_PACKAGE} 2>/dev/null || echo none)
    # the status filter matters: dpkg lists a removed-but-not-purged package too, and that
    # one owns no repository file
    RELEASE=\$(dpkg-query -W -f='\${db:Status-Abbrev} \${Package}\\n' '${REPO_PACKAGE_GLOB}' 2>/dev/null \\
        | awk '\$1 ~ /^i/ { printf "%s${LIST_SEPARATOR}", \$2 }' || true)
elif [ "\$PKGFMT" = rpm ]; then
    PKGVERSION=\$(rpm -q --qf '%{EVR}' ${SENSOR_PACKAGE} 2>/dev/null || echo none)
    RELEASE=\$(rpm -qa '${REPO_PACKAGE_GLOB}' --qf '%{NAME}${LIST_SEPARATOR}' 2>/dev/null || true)
fi

# a repository pointing at Invary that no package owns was configured by hand or by the
# customer's own automation, and may resolve the sensor from a channel this install did not
# ask for
FOREIGN=""
for f in /etc/yum.repos.d/*.repo /etc/apt/sources.list.d/*.list; do
    [ -f "\$f" ] || continue
    grep -q '${PACKAGES_HOST_NAME}' "\$f" 2>/dev/null || continue
    if [ "\$PKGFMT" = rpm ] && rpm -qf "\$f" >/dev/null 2>&1; then continue; fi
    if [ "\$PKGFMT" = deb ] && dpkg -S "\$f" >/dev/null 2>&1; then continue; fi
    FOREIGN="\$FOREIGN\$f${LIST_SEPARATOR}"
done

echo "arch=\$(uname -m)"
echo "installed=\$([ -f ${LINUX_BINARY} ] && echo yes || echo no)"
echo "pkgfmt=\$PKGFMT"
echo "pkgtool=\$PKGTOOL"
echo "shellinstall=\$SHELLINSTALL"
echo "pkgversion=\${PKGVERSION:-none}"
echo "release=\${RELEASE:-none}"
echo "foreignrepo=\${FOREIGN:-none}"
echo "config=\$([ -f ${LINUX_CONFIG} ] && base64 -w0 ${LINUX_CONFIG} || true)"
"""
    }

    /** The values a probe reported back. */
    @ToString(includeNames = true, includePackage = false, excludes = ['config'])
    static class Probe {
        /** The package architecture, `x86_64` or `aarch64`. */
        String arch

        /** Whether a sensor is already installed on the server. */
        boolean installed

        /** The decoded contents of an existing configuration file, or null. */
        String config

        /** The package format the server uses, `deb`, `rpm`, or null when it uses neither. */
        String pkgFmt

        /** The package manager to drive, `apt-get`, `dnf`, `yum`, or null when there is none. */
        String pkgTool

        /** Whether the sensor present was installed by the shell installer rather than a package. */
        boolean shellInstall

        /** The version of the installed sensor package, or null when it is not installed. */
        String pkgVersion

        /**
         * Every Invary repo package installed, which names the channel the server is configured
         * for. A list rather than one name because two can coexist if the packages do not
         * declare each other as conflicts.
         */
        List<String> repoPackages = []

        /** Repository files pointing at Invary that no package owns. */
        List<String> foreignRepos = []
    }

    /**
     * Reads the output of a probe.
     *
     * @throws IllegalArgumentException if the architecture is missing or unsupported
     */
    static Probe parseProbe(String output) {
        String encoded = field(output, 'config')

        return new Probe(
            arch: packageArch(field(output, 'arch'), output),
            installed: field(output, 'installed') == 'yes',
            config: encoded ? new String(encoded.decodeBase64(), 'UTF-8') : null,
            pkgFmt: reported(field(output, 'pkgfmt')),
            pkgTool: reported(field(output, 'pkgtool')),
            shellInstall: field(output, 'shellinstall') == 'yes',
            pkgVersion: reported(field(output, 'pkgversion')),
            repoPackages: list(field(output, 'release')),
            foreignRepos: list(field(output, 'foreignrepo')),
        )
    }

    /** A probe reports an absent value as `none`, which is not a value. */
    private static String reported(String value) {
        String trimmed = value?.trim()
        return (!trimmed || trimmed == 'none') ? null : trimmed
    }

    /** Reads a reported list, which is separator terminated rather than separator joined. */
    private static List<String> list(String value) {
        String reported = reported(value)
        return reported ? reported.tokenize(LIST_SEPARATOR).findAll { it?.trim() } : []
    }

    /**
     * Reads one reported value out of a probe's output.
     *
     * The values are matched wherever they appear rather than by splitting each line, because
     * how a command's output reaches the plugin depends on the transport Morpheus used to run
     * it - some report it as plain lines and others as a rendered map of process state.
     */
    private static String field(String text, String name) {
        if (!text) {
            return null
        }

        // a value may be preceded by the start of the output, whitespace, or the punctuation
        // of a rendered map such as `[success:true, output:arch=x86_64`
        def matcher = text =~ ('(?:^|[\\s,\\[:])' + Pattern.quote(name) + '=([^\\s,\\]]*)')
        return matcher.find() ? matcher.group(1) : null
    }

    /**
     * Maps what a server calls its architecture onto the name the sensor package API uses.
     *
     * @throws IllegalArgumentException if the architecture is not one packages are built for
     */
    static String packageArch(String reported) {
        return packageArch(reported, null)
    }

    /**
     * @param probed the raw probe output, quoted back in the error when nothing was reported,
     *               since that is the only way to see what the target actually said
     */
    static String packageArch(String reported, String probed) {
        switch (reported?.trim()?.toLowerCase()) {
            case 'x86_64':
            case 'amd64':
                return 'x86_64'
            case 'aarch64':
            case 'arm64':
                return 'aarch64'
            case null:
            case '':
                throw new IllegalArgumentException(
                    'The architecture of the target could not be determined. The target reported: ' +
                    (probed?.trim() ? excerpt(probed) : '(nothing)'))
            default:
                throw new IllegalArgumentException(
                    "The Invary Sensor is not available for the architecture '${reported}' of the target.")
        }
    }

    private static String excerpt(String text) {
        String flattened = text.trim().replaceAll(/\s+/, ' ')
        return flattened.length() > 300 ? flattened.substring(0, 300) + '...' : flattened
    }

    // -- Configuration merge --------------------------------------------------

    /**
     * Produces the toml configuration file to install.
     *
     * A managed value is rewritten where it already stands, so a configuration the plugin has
     * already set comes back exactly as it was found: its tables, their order, its comments and
     * its blank lines are all left alone. A managed key the file does not hold is added to the
     * table it belongs to, and that table is written only when the file has none.
     *
     * Writing a managed key as a top level dotted path instead would declare a table that a
     * `[connect]` or `[measurement]` header further down the file declares a second time. That
     * is not valid TOML, and the sensor refuses to start on a configuration it cannot parse.
     *
     * @param existing the current contents of the configuration file, or null when there is none
     */
    static String mergeToml(String existing, InvarySensorConfig config) {
        List<Section> sections = sections(existing)
        Map<String, String> values = managedValues(sections, config)
        Set<String> rewritten = rewrite(sections, values)

        // a null value is a removal, so there is nothing to add for it
        MANAGED.findAll { values[it['key']] != null && !rewritten.contains(it['key']) }
            .groupBy { it['table'] as String }
            .each { table, keys -> add(sections, table as String, keys, values) }

        return render(sections)
    }

    /** One `[table]` of a configuration, or the keys written before the first one. */
    private static class Section {
        /** The header as it was written, or null for the keys before the first table. */
        String header

        /** The table the header names, or the empty string before the first one. */
        String table = ''

        /** Whether the header declares an array of tables, which is never written into. */
        boolean array

        /** Every line of the section but its header, as it was written. */
        List<String> lines = []
    }

    /** Reads a configuration into its sections. */
    private static List<Section> sections(String existing) {
        List<Section> sections = [new Section()]
        if (!existing?.trim()) {
            return sections
        }

        existing.readLines().each { line ->
            if (line.trim() == REMOVED_COMMENT) {
                return
            }

            def header = TABLE.matcher(line)
            if (header.matches()) {
                sections << new Section(
                    header: line,
                    table: qualify('', header.group(1)),
                    array: line.trim().startsWith('[['),
                )
            } else {
                sections.last().lines << line
            }
        }

        return sections
    }

    /**
     * Rewrites the value of every managed assignment where it stands, dropping any second
     * assignment of the same key, and reports which keys the configuration held.
     *
     * An assignment is rewritten as the key and its new value, so anything else on that line,
     * such as a comment about the value it used to hold, does not survive.
     */
    private static Set<String> rewrite(List<Section> sections, Map<String, String> values) {
        Set<String> rewritten = []

        sections.each { section ->
            List<String> lines = section.lines
            List<String> kept = []

            for (int i = 0; i < lines.size(); i++) {
                String line = lines[i]

                def assignment = ASSIGNMENT.matcher(line)
                if (!assignment.matches()) {
                    kept << line
                    continue
                }

                String key = qualify(section.table, assignment.group(1))

                // a managed key this install expresses no opinion on belongs to the operator
                // and is kept exactly as written. Checked before the array below is consumed,
                // so that a kept value keeps every line of itself
                if (section.array || !MANAGED_KEYS.contains(key) || !values.containsKey(key)) {
                    kept << line
                    continue
                }

                // a value written as an array across several lines is replaced by the one line
                // the new value is written on, or removed along with it
                int depth = bracketDepth(assignment.group(2))
                while (depth > 0 && i + 1 < lines.size()) {
                    i++
                    depth += bracketDepth(lines[i])
                }

                // a null value removes the assignment instead of rewriting it, leaving the
                // sensor to measure at the interval it would use on its own
                if (values[key] == null) {
                    continue
                }

                if (rewritten.add(key)) {
                    kept << "${indentOf(line)}${assignment.group(1).trim()} = ${values[key]}".toString()
                }
            }

            section.lines = kept
        }

        return rewritten
    }

    /**
     * The value to write for each managed key, given what the configuration already holds.
     *
     * Three states:
     *
     * <ul>
     *   <li>a key with a value is written</li>
     *   <li>a key mapped to null is removed from the configuration</li>
     *   <li>a key that is absent is left exactly as the configuration holds it</li>
     * </ul>
     *
     * Absent is the default for the interval, because operators appraise critical machines more
     * often than the rest by setting a shorter interval on those machines. An install that
     * expresses no opinion must not take that away, so clearing one has to be asked for.
     */
    private static Map<String, String> managedValues(List<Section> sections, InvarySensorConfig config) {
        Map<String, String> values = [
            'tags'                   : tomlArray(mergeTags(existingTags(sections), config.tags)),
            'connect.uri'            : tomlString(config.sensorUrl),
            'connect.allow.untrusted': config.allowUntrusted.toString(),
        ]

        if (config.measurementInterval == InvarySensorConfig.INTERVAL_CLEAR) {
            values['measurement.interval'] = null
        } else if (config.measurementInterval != null) {
            values['measurement.interval'] = config.measurementInterval.toString()
        }

        return values
    }

    /**
     * Combines the tags a configuration already holds with the ones this install sets.
     *
     * A tag the operator added is kept, so that an install which changes nothing leaves the file
     * as it found it. A tag the plugin owns is kept only while this install still sets it, so
     * that moving a machine to another instance, or changing what is done about a failed
     * appraisal, does not leave the tag it had before alongside the new one.
     */
    static List<String> mergeTags(List<String> existing, List<String> installed) {
        List<String> merged = (existing ?: []).findAll {
            installed.contains(it) || !it.startsWith(InvarySensorConfig.TAG_PREFIX)
        }

        (installed ?: []).each { tag ->
            if (!merged.contains(tag)) {
                merged << tag
            }
        }

        return merged
    }

    /** The tags a configuration already holds, in the order it holds them. */
    private static List<String> existingTags(List<Section> sections) {
        for (Section section : sections) {
            if (section.array) {
                continue
            }

            List<String> lines = section.lines
            for (int i = 0; i < lines.size(); i++) {
                def assignment = ASSIGNMENT.matcher(lines[i])
                if (!assignment.matches() || qualify(section.table, assignment.group(1)) != 'tags') {
                    continue
                }

                StringBuilder value = new StringBuilder(assignment.group(2))
                int depth = bracketDepth(assignment.group(2))
                while (depth > 0 && i + 1 < lines.size()) {
                    i++
                    depth += bracketDepth(lines[i])
                    value.append('\n').append(lines[i])
                }

                return tomlStrings(value.toString())
            }
        }

        return []
    }

    /**
     * Adds the keys a configuration does not hold to the table they belong to.
     *
     * The table is written only when the configuration neither declares it with a header nor
     * already names it in a top level dotted key: a table a dotted key has declared cannot be
     * declared by a header as well, so a sibling key joins it as a dotted key instead.
     *
     * An assignment goes before the blank line that separates a section from the next one,
     * which belongs to the section it follows.
     */
    private static void add(List<Section> sections, String table, List<Map> keys, Map<String, String> values) {
        Section top = sections.first()
        Section section = table ? sections.find { it.table == table && !it.array } : top
        boolean dotted = false

        if (!section && declaredByDottedKey(top, table)) {
            section = top
            dotted = true
        }

        if (!section) {
            section = new Section(header: "[${table}]".toString(), table: table)
            sections << section
        }

        List<String> assignments = keys.collect {
            "${dotted ? it['key'] : it['name']} = ${values[it['key']]}".toString()
        }

        int at = section.lines.size()
        while (at > 0 && !section.lines[at - 1].trim()) {
            at--
        }

        section.lines.addAll(at, assignments)
    }

    /** Whether a top level dotted key already names a table, which a header then may not. */
    private static boolean declaredByDottedKey(Section top, String table) {
        return top.lines.any { line ->
            def assignment = ASSIGNMENT.matcher(line)
            assignment.matches() && qualify('', assignment.group(1)).startsWith("${table}.")
        }
    }

    /** Writes the sections back out, ending in a single newline. */
    private static String render(List<Section> sections) {
        List<String> out = []

        sections.each { section ->
            if (section.header) {
                // a section this merge wrote has no blank line of its own before it
                if (out && out.last().trim()) {
                    out << ''
                }

                out << section.header
            }

            out.addAll(section.lines)
        }

        while (!out.isEmpty() && !out.last().trim()) {
            out.remove(out.size() - 1)
        }

        return out.isEmpty() ? '' : out.join('\n') + '\n'
    }

    private static String indentOf(String line) {
        int at = 0
        while (at < line.length() && Character.isWhitespace(line.charAt(at))) {
            at++
        }

        return line.substring(0, at)
    }

    /** Combines the enclosing table with a key into one dotted path, as the sensor sees it. */
    private static String qualify(String table, String key) {
        String bare = key.split(/\./).collect { unquote(it.trim()) }.join('.')
        return table ? "${table}.${bare}" : bare
    }

    private static String unquote(String value) {
        boolean quoted = value.length() >= 2 &&
            ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'")))

        return quoted ? value.substring(1, value.length() - 1) : value
    }

    /** How far a line opens or closes an array, ignoring brackets inside strings. */
    private static int bracketDepth(String value) {
        int depth = 0
        boolean inString = false
        Character quote = null

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i)

            if (inString) {
                if (c == '\\' as char) {
                    i++
                } else if (c == quote) {
                    inString = false
                }
                continue
            }

            if (c == '"' as char || c == "'" as char) {
                inString = true
                quote = c
            } else if (c == '#' as char) {
                break
            } else if (c == '[' as char) {
                depth++
            } else if (c == ']' as char) {
                depth--
            }
        }

        return depth
    }

    static String tomlArray(List<String> values) {
        return '[' + (values ?: []).collect { tomlString(it) }.join(', ') + ']'
    }

    static String tomlString(String value) {
        return '"' + (value ?: '').replace('\\', '\\\\').replace('"', '\\"') + '"'
    }

    /** A basic or literal TOML string, capturing its contents. */
    private static final Pattern STRING = ~/"((?:[^"\\]|\\.)*)"|'([^']*)'/

    /** The strings of a TOML array, as the sensor reads them. */
    static List<String> tomlStrings(String value) {
        List<String> strings = []
        def matcher = STRING.matcher(value ?: '')

        while (matcher.find()) {
            strings << (matcher.group(1) != null ? unescape(matcher.group(1)) : matcher.group(2))
        }

        return strings
    }

    /** Reads the contents of a basic string, where a backslash quotes the character after it. */
    private static String unescape(String value) {
        StringBuilder out = new StringBuilder()

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i)
            if (c == '\\' as char && i + 1 < value.length()) {
                i++
                out.append(value.charAt(i))
            } else {
                out.append(c)
            }
        }

        return out.toString()
    }

    // -- Install scripts ------------------------------------------------------

    /**
     * The part of a Linux install that is the same however the sensor is delivered: the
     * failure reporting, the service handling, and the backups a failed run is put back from.
     *
     * Both install scripts are built on this, so the transcript a failure produces has one
     * implementation and {@link #parseFailure} and {@link #parseSummary} have one contract to
     * read.
     *
     * @param extraVars   variable declarations the caller's steps need, joined to the ones here
     * @param restoreExtra what a failed run puts back, run after the diagnostics have been
     *                     collected and the sensor stopped, and before the failure marker
     * @param cleanupExtra what is removed however the run ends
     */
    private static String linuxPreamble(String extraVars, String restoreExtra, String cleanupExtra) {
        return """\
#!/bin/bash
set -Eeuo pipefail

# both streams are reported together so that the output of a failing command arrives next to
# the step it failed in, whichever stream it was written to. How a command's output reaches
# the plugin depends on the transport Morpheus ran it over, and only one stream may survive
exec 2>&1

CFG=${LINUX_CONFIG}
BIN=${LINUX_BINARY}
STAMP=\$(date +%Y%m%d%H%M%S)
BAK=""
BINBAK=""
STEP="preparing to install"
${extraVars}
# names the step being run, so that a failure can report where it happened
step() {
    STEP="\$1"
    echo "==> \$1"
}

# whether any of the sensor is still there: the unit running, or a process of its own that has
# outlived it. A unit that keeps restarting reports neither inactive nor failed
running() {
    state=\$(systemctl show -p ActiveState --value ${LINUX_SERVICE} 2>/dev/null || true)
    case "\$state" in
        inactive|failed|'')
            ;;
        *)
            return 0
            ;;
    esac

    # matched on the command line rather than on the process name, which the sensor renames as
    # it starts
    if command -v pgrep >/dev/null 2>&1 && pgrep -f "^${LINUX_BINARY}( |\$)" >/dev/null 2>&1; then
        return 0
    fi

    return 1
}

# stops the sensor and waits for it to be gone. A stop is not finished when systemctl returns,
# and a sensor started while the one before it is still running measures against probes that
# the one before it owns
stop_sensor() {
    systemctl stop ${LINUX_SERVICE} >/dev/null 2>&1 || true

    waited=0
    while [ "\$waited" -lt ${STOP_SECONDS} ]; do
        if ! running; then
            return 0
        fi

        sleep 1
        waited=\$((waited + 1))
    done

    echo "The ${LINUX_SERVICE} service was still running ${STOP_SECONDS} seconds after being stopped"
    return 1
}

# how many times the service has been restarted for itself, which is how a sensor that keeps
# being killed is told from one that is running
restarts() {
    systemctl show -p NRestarts --value ${LINUX_SERVICE} 2>/dev/null || echo 0
}

# watches a started sensor for long enough to see it go. One that is killed and restarted is
# running whenever it is sampled, so asking whether it is up reports every such sensor as
# installed, however short its life
watch_sensor() {
    started=\$(restarts)
    waited=0

    while [ "\$waited" -lt ${WATCH_SECONDS} ]; do
        sleep 1
        waited=\$((waited + 1))

        if ! systemctl is-active --quiet ${LINUX_SERVICE}; then
            echo "The ${LINUX_SERVICE} service stopped \$waited seconds after it was started"
            return 1
        fi

        now=\$(restarts)
        if [ "\$now" != "\$started" ]; then
            echo "The ${LINUX_SERVICE} service was restarted \$waited seconds after it was started, and has now been restarted \$now times"
            return 1
        fi
    done

    return 0
}

# reports the end of a file, and says so when there is nothing to report rather than leaving
# an empty section, which reads as though there was nothing wrong
tail_file() {
    echo "--- \$1 (last \$2 lines) ---"
    if [ -r "\$1" ]; then
        tail -n "\$2" "\$1" || true
        # a file that ends without a newline would otherwise run into the next line
        echo
    else
        echo "(no such file)"
    fi
}

# what diagnosing a failed install needs, none of which is reported by an exit code: the
# state of the service, the status the sensor exited with, what the sensor logged, and the
# configuration it was given. Each is best effort, since a run that failed before the sensor
# was installed has none of it to report
#
# The repository files are deliberately not among them. /etc/yum.repos.d/*.repo and
# /etc/apt/auth.conf.d/* carry the repository password, and this transcript is reported back
# and stored as task history
diagnostics() {
    # the exited status and the restart count say what the service state alone does not: a
    # sensor that keeps exiting is restarted, which reads as activating rather than as failed
    echo "--- systemctl show ${LINUX_SERVICE} ---"
    systemctl show ${LINUX_SERVICE} --no-pager \\
        --property=LoadState,ActiveState,SubState,Result,ExecMainStatus,ExecMainCode,NRestarts,ExecStart || true
    echo "--- systemctl status ${LINUX_SERVICE} ---"
    systemctl status ${LINUX_SERVICE} --no-pager --full || true
    echo "--- journalctl -u ${LINUX_SERVICE} (last ${JOURNAL_LINES} lines) ---"
    journalctl -u ${LINUX_SERVICE} --no-pager --full --lines=${JOURNAL_LINES} || true

    # the sensor logs to a file rather than to stderr, so the journal above holds only the
    # unit's lifecycle and the reason it stopped is in one of these. Which file it is depends
    # on how the sensor was started, so every log there is reported
    echo "--- ${LINUX_VAR_DIR} ---"
    ls -l ${LINUX_VAR_DIR} || true
    for f in ${LINUX_VAR_DIR}/*.err ${LINUX_VAR_DIR}/*.out ${LINUX_VAR_DIR}/*.log; do
        # an unmatched pattern arrives here as itself, which is not a file
        if [ -f "\$f" ]; then
            tail_file "\$f" ${JOURNAL_LINES}
        fi
    done

    tail_file "\$CFG" ${CONFIG_LINES}
    echo "--- end of diagnostics ---"
}

# says why the run failed in as few lines as possible: the status the sensor exited with, and
# the logged lines that explain it. This is what is reported as the failure, so it is written
# last, where a message elided in the middle still carries it
summary() {
    echo "${SUMMARY_MARKER}"

    # on one line, since the part that matters is the status the sensor exited with
    systemctl show ${LINUX_SERVICE} --no-pager \\
        --property=ActiveState,SubState,Result,ExecMainStatus,NRestarts 2>/dev/null | tr '\\n' ' ' || true
    echo

    # a sensor that fails while loading its configuration exits before it has a log file to
    # write to and reports the reason on stderr, which only the journal holds, so the journal
    # is always reported and reported first
    echo "journal:"
    journalctl -u ${LINUX_SERVICE} --no-pager --lines=${CAUSE_LINES} 2>/dev/null || true

    for f in ${LINUX_VAR_DIR}/*.err ${LINUX_VAR_DIR}/*.out ${LINUX_VAR_DIR}/*.log; do
        if [ -f "\$f" ]; then
            # the lines that explain a failure, falling back to the end of the log when none
            # of them stand out
            lines=\$(grep -aiE '${CAUSE_PATTERN}' "\$f" 2>/dev/null | tail -n ${CAUSE_LINES} || true)
            if [ -z "\$lines" ]; then
                lines=\$(tail -n 3 "\$f" 2>/dev/null || true)
            fi

            if [ -n "\$lines" ]; then
                # a log left by an earlier run says nothing about this one, so when it was
                # last written is reported with it
                echo "\$f (last written \$(date -r "\$f" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)):"
                echo "\$lines"
            fi
        fi
    done
}

restore() {
    rc=\$?

    # the diagnostics run commands that report a stopped service by failing, which must not
    # re-enter this handler
    trap - ERR

    echo "The Invary Sensor install failed while \$STEP (exit \$rc)"

    # nothing either of these do may stop the marker below from being written. Both run before
    # the restore, so that they describe the failed sensor rather than the one put back
    diagnostics || true
    summary || true

    # nothing can be put back under a running sensor: the file its process is executing cannot
    # be written to
    stop_sensor || true
${restoreExtra}
    echo "${FAILURE_MARKER} exit=\$rc step=\$STEP"
    exit \$rc
}

# whatever this run downloaded or unpacked is removed however it ends, so a failure part way
# through does not leave it behind
cleanup() {
    cd /

    if [ -n "\$BINBAK" ]; then
        rm -f "\$BINBAK"
    fi
${cleanupExtra}
}

trap restore ERR
trap cleanup EXIT

if [ -f "\$CFG" ]; then
    BAK="\$CFG.morpheus-\$STAMP.bak"
    cp -a "\$CFG" "\$BAK"
    echo "Backed up existing configuration to \$BAK"
fi

# a failed install puts the sensor back as it was, which is the version it was running as well
# as the configuration it was running on. This copy is removed however the run ends, since by
# then it has either been put back or is not wanted
if [ -f "\$BIN" ]; then
    BINBAK="\$BIN.morpheus-\$STAMP.bak"
    cp -a "\$BIN" "\$BINBAK"
    echo "Backed up existing sensor binary to \$BINBAK"
fi
"""
    }

    /** Writes the merged configuration, which is the same however the sensor was delivered. */
    private static String writeConfigStep(String mergedToml) {
        String encoded = mergedToml.getBytes('UTF-8').encodeBase64().toString()

        return """\
step "writing \$CFG"
mkdir -p "\$(dirname "\$CFG")"
base64 -d > "\$CFG" <<'INVARY_TOML_B64'
${encoded}
INVARY_TOML_B64
# the file names an appraiser and carries the machine's tags, and nothing but root reads it
chmod 0600 "\$CFG"
"""
    }

    /**
     * Writes the repo definition package taken from the customer package.
     *
     * The appliance fetched it, so the node needs no access to whatever serves the customer
     * package, and a few kilobytes cross the wire instead of the hundred megabytes that archive
     * runs to.
     */
    private static String writeRepoPackageStep(byte[] repoPackageBytes) {
        String encoded = repoPackageBytes.encodeBase64().toString()
            .toList().collate(76)*.join('').join('\n')

        return """\
step "writing the Invary repo package"
base64 -d > "\$RELPKG" <<'INVARY_REPO_B64'
${encoded}
INVARY_REPO_B64
# the package carries the repository credentials, and nothing but root reads it
chmod 0600 "\$RELPKG"
"""
    }

    /**
     * Builds the script that installs and configures the sensor on a Linux server from the
     * Invary package repository.
     *
     * The server is pointed at a channel by installing the repo package taken from the customer
     * package, which owns the repository file, the credentials and the signing key. Nothing here
     * writes repository configuration of its own.
     *
     * Unlike the shell installer, neither package manager starts the sensor: a fresh install
     * runs `systemctl preset`, which enables without starting, and an upgrade runs
     * `try-restart`, which does nothing to a service this script has already stopped. So nothing
     * has to refuse a start, and the only start of the new sensor is the one at the end of this
     * script.
     *
     * @param probe what the server reported about itself, which decides the package manager, the
     *              conversion and the conflict handling
     * @param mergedToml the configuration to install, from {@link #mergeToml}
     * @param repoPackageBytes the repo definition package, from {@link InvaryCustomerPackage}
     */
    static String linuxPackageScript(InvarySensorConfig config, Probe probe, String mergedToml,
                                     byte[] repoPackageBytes) {
        boolean deb = probe.pkgFmt == 'deb'
        String tool = probe.pkgTool

        String vars = """
RELPKG=\$(mktemp /tmp/invary-release-XXXXXX)
UNITBAK=/tmp/invary-unit-\$STAMP.bak
REPOBAK=/tmp/invary-repo-\$STAMP
CONVERTED=no
REPO_PKG=""
OLD_REPO_PKG=""
OLD_CHANNEL=""
NEW_CHANNEL=""
REPO_SWAPPED=no

# the channel a repo package name carries. The customer package decides which one it holds, so
# this is how the old and the new are named the same way when a run moves between them
channel_of() {
    case "\$1" in
        *-release)   echo stable ;;
        *-release-*) echo "\${1##*-release-}" ;;
        *)           echo unknown ;;
    esac
}
"""

        // the conversion is undone before anything else, since the package installed over it
        // owns the same paths. A run that never converted leaves this alone
        String restoreExtra = """
    if [ "\$CONVERTED" = yes ]; then
        echo "Putting back the shell installation this run replaced"
        # remove, never purge. The package's postremove deletes /opt/invary/conf/invary.toml
        # and all of ${LINUX_VAR_DIR} when purging, and the latter holds the sensor's
        # identity -- a machine that loses it has to be provisioned again. On rpm that is
        # opted into through the environment, so the variable is pinned off here rather than
        # assumed absent: this runs while an install is already failing, and it is the one
        # place data loss would be unrecoverable
        ${deb ? "dpkg -r ${SENSOR_PACKAGE}" : "INVARY_PURGE=0 rpm -e ${SENSOR_PACKAGE}"} >/dev/null 2>&1 || true

        if [ -f "\$UNITBAK" ]; then
            cp -a "\$UNITBAK" /etc/systemd/system/${LINUX_SERVICE}.service || true
            systemctl daemon-reload 2>/dev/null || true
        fi
    fi

    # the repository this run pointed the machine at is undone before the sensor is, so a failed
    # install leaves the machine resolving the sensor from the channel it was already on
    if [ "\$REPO_SWAPPED" = yes ]; then
        # the package installed over the old one goes first: putting the old files back
        # underneath it would leave two enabled Invary repositories
        if [ -n "\$REPO_PKG" ]; then
            ${deb ? "dpkg -r \"\$REPO_PKG\"" : "rpm -e \"\$REPO_PKG\""} >/dev/null 2>&1 || true
        fi

        # an empty backup is a backup that failed, and reporting a restore from one would say
        # the machine was put back when nothing was
        if [ -n "\$(ls -A "\$REPOBAK" 2>/dev/null)" ] && cp -a "\$REPOBAK"/. / 2>/dev/null; then
            echo "Restored the previous Invary package repository (\${NEW_CHANNEL:-unknown} -> \${OLD_CHANNEL:-unknown})"
        else
            echo "The previous Invary package repository could not be restored from \$REPOBAK"
        fi
    fi

    if [ -n "\$BINBAK" ] && [ -f "\$BINBAK" ]; then
        if cp -a "\$BINBAK" "\$BIN"; then
            echo "Restored the previous sensor binary"
        else
            echo "The previous sensor binary could not be restored from \$BINBAK"
        fi
    fi

    if [ -n "\$BAK" ] && [ -f "\$BAK" ]; then
        if cp -a "\$BAK" "\$CFG"; then
            echo "Restored the previous configuration"
        else
            echo "The previous configuration could not be restored from \$BAK"
        fi
    fi

    # a run that had nothing to put back installed onto a server that had no sensor, and leaves
    # it stopped rather than removing it: the package is inert while it is not running, and
    # removing it would take the evidence the diagnostics above were collected from
    if [ -n "\$BINBAK" ] || [ -n "\$BAK" ]; then
        systemctl start ${LINUX_SERVICE} >/dev/null 2>&1 || true
    fi
"""

        // the repo package and the backup of the one it replaced both carry the repository
        // credentials, so neither is left behind however the run ends
        String cleanupBlock = """
    rm -f "\$RELPKG"
    rm -f "\$UNITBAK"
    rm -rf "\$REPOBAK"
"""

        StringBuilder script = new StringBuilder(linuxPreamble(vars, restoreExtra, cleanupBlock))

        if (probe.shellInstall) {
            script.append(shellInstallConversion())
        }

        script.append('\n')
        script.append(writeRepoPackageStep(repoPackageBytes))
        script.append(repoPackageIdentity(deb))
        script.append(conflictRemoval(probe, deb))
        script.append(deb ? debRepoSteps() : rpmRepoSteps(tool))

        script.append("""
step "stopping the ${LINUX_SERVICE} service"
stop_sensor
""")

        script.append(deb ? debInstallSteps() : rpmInstallSteps(tool))

        script.append("""
# the package enables the service on a fresh install and leaves an upgrade alone, but it
# cannot tell those apart after a remove that was not a purge: dpkg reports the version it
# remembers either way, so the package takes its upgrade path and the unit is never enabled.
# The sensor then runs until the machine reboots and never again, with nothing said about it.
# This install's own rollback removes without purging, so its retry lands in exactly that
# state. Asserting costs one command and says nothing when it was already right
if ! systemctl is-enabled ${LINUX_SERVICE} >/dev/null 2>&1; then
    echo "The ${LINUX_SERVICE} service was installed but not enabled; enabling it so it starts at boot"
    systemctl enable ${LINUX_SERVICE} >/dev/null 2>&1 || true
fi
""")

        script.append(deb ? debMembershipStep() : rpmMembershipStep(tool))

        script.append('\n')
        script.append(writeConfigStep(mergedToml))

        script.append("""
# reports a configuration the sensor cannot work with as a configuration problem, rather than
# as a service that starts and immediately exits. It reads the file written above and reaches
# the appraiser named in it, and it honours connect.allow.untrusted, so an appraiser this
# install was told to trust does not fail it here
#
# Exit codes are from application/sensor/src/check.rs: 2 configuration file, 3 kernel symbols,
# 4 kernel types, 5 eBPF support, 6 connectivity and certificate, 78 implies more than one failure.
step "checking the sensor configuration"
CHECK_RC=0
${LINUX_BINARY} check || CHECK_RC=\$?

if [ "\$CHECK_RC" = 6 ]; then
    # an unreachable appraiser does not stop a sensor: it starts, stays up and keeps trying,
    # so failing here would abandon an install that is correct and would have recovered on its
    # own. Every other failure is structural and the sensor cannot resolve it by waiting
    echo "The Invary Sensor cannot reach the appraiser at ${config.sensorUrl} from this machine."
    echo "Connectivity was the only check that failed, so disregard the kernel advice above:"
    echo "the sensor is installed and configured, and reports as soon as the appraiser answers."
    echo "If this machine never appears, look for a firewall between it and that address."
elif [ "\$CHECK_RC" != 0 ]; then
    case "\$CHECK_RC" in
        2)     echo "The Invary Sensor rejected the configuration written to \$CFG." ;;
        3|4|5) echo "This machine cannot give the Invary Sensor what it needs to measure its kernel." ;;
        78)    echo "More than one check failed. Each one is reported above." ;;
        *)     echo "The Invary Sensor configuration check failed." ;;
    esac

    exit \$CHECK_RC
fi

step "starting the ${LINUX_SERVICE} service"
systemctl start ${LINUX_SERVICE}

# a sensor that rejects its configuration, or that is killed as it measures, exits shortly
# after starting and is started again, which a check made the moment the start returns, or any
# single check, still reports as running
watch_sensor

echo "Invary Sensor installed"
""")

        return script.toString()
    }

    /**
     * Dismantles an install made by the shell installer, which the package refuses to unpack
     * over.
     *
     * Exactly the steps the package's own preinstall prints, and no more. In particular this is
     * not invary-uninstall.sh, which removes /var/opt/invary/data along with everything else -
     * that directory holds the sensor's identity, and a machine that loses it has to be
     * provisioned again. The configuration and the data directory are both left untouched, so
     * the switch keeps the machine as the appraiser already knows it.
     */
    private static String shellInstallConversion() {
        return """
step "converting the shell installation"
if [ -f /etc/systemd/system/${LINUX_SERVICE}.service ] && [ ! -L /etc/systemd/system/${LINUX_SERVICE}.service ]; then
    cp -a /etc/systemd/system/${LINUX_SERVICE}.service "\$UNITBAK"
fi

systemctl disable --now ${LINUX_SERVICE}.service >/dev/null 2>&1 || true
rm -f /etc/systemd/system/${LINUX_SERVICE}.service /etc/init.d/${LINUX_SERVICE}
rm -f ${LINUX_BINARY}
systemctl daemon-reload
CONVERTED=yes
echo "The shell installation was removed; the configuration and ${LINUX_VAR_DIR} were kept"
"""
    }

    /**
     * Reads which repo package the customer package turned out to hold.
     *
     * The file name carries no channel - the same name holds the release, next or staging
     * package - so the name, and with it the channel, is only knowable from the package itself.
     */
    private static String repoPackageIdentity(boolean deb) {
        return """
step "reading the Invary repo package"
REPO_PKG=\$(${deb ? "dpkg-deb -f \"\$RELPKG\" Package" : "rpm -qp --qf '%{NAME}' \"\$RELPKG\""} 2>/dev/null || true)
if [ -z "\$REPO_PKG" ]; then
    echo "The repo package from the customer package declares no package name"
    exit 1
fi

NEW_CHANNEL=\$(channel_of "\$REPO_PKG")
echo "The customer package configures the \$NEW_CHANNEL channel (\$REPO_PKG)"
"""
    }

    /**
     * Replaces whatever Invary repository the machine already carries.
     *
     * A machine can only be configured for one channel: two enabled repositories both carry a
     * package named `invary`, and the package managers resolve that by version rather than by
     * intent. The repo packages declare each other as conflicts, so the old one has to go before
     * the new one will unpack at all.
     *
     * The files of the package removed are kept, so that a run which fails later can put the
     * machine back on the channel it was found on. They carry the repository password, which is
     * why the backup is private and is removed however the run ends.
     */
    private static String conflictRemoval(Probe probe, boolean deb) {
        if (!probe.repoPackages && !probe.foreignRepos) {
            return ''
        }

        StringBuilder step = new StringBuilder("""
step "replacing the Invary repository this machine carries"
mkdir -p "\$REPOBAK"
chmod 0700 "\$REPOBAK"
""")

        if (probe.repoPackages) {
            String owned = probe.repoPackages.collect { "'${it}'" }.join(' ')

            step.append("""
for old in ${owned}; do
    if [ "\$old" = "\$REPO_PKG" ]; then
        echo "This machine is already on the \$NEW_CHANNEL channel (\$old)"
        continue
    fi

    OLD_REPO_PKG="\$old"
    OLD_CHANNEL=\$(channel_of "\$old")

    # the listing is guarded rather than relied on: this runs under pipefail, and a package the
    # probe saw but the manager will not list must not fail the install by itself
    { ${deb ? 'dpkg -L' : 'rpm -ql'} "\$old" 2>/dev/null || true; } | while read -r owned_file; do
        if [ -f "\$owned_file" ]; then
            cp -a --parents "\$owned_file" "\$REPOBAK" 2>/dev/null || true
        fi
    done

    ${deb ? "dpkg -r \"\$old\"" : "rpm -e \"\$old\""}
    REPO_SWAPPED=yes
done
""")
        }

        if (probe.foreignRepos) {
            // a repository file no package owns was written by hand or by the customer's own
            // automation. Left in place it resolves the sensor from a second channel, which is
            // the state removing the owned package above exists to prevent
            String files = probe.foreignRepos.collect { "'${it}'" }.join(' ')

            step.append("""
for unowned in ${files}; do
    if [ -f "\$unowned" ]; then
        echo "Removing \$unowned, an Invary repository file no package owns"
        cp -a --parents "\$unowned" "\$REPOBAK" 2>/dev/null || true
        rm -f "\$unowned"
        REPO_SWAPPED=yes
    fi
done
""")
        }

        // said once, after everything that could have changed the machine's channel, so that a
        // re-run onto the channel the machine is already on stays quiet
        step.append("""
if [ -n "\$OLD_REPO_PKG" ]; then
    echo "Invary package channel changed: \$OLD_CHANNEL -> \$NEW_CHANNEL (\$OLD_REPO_PKG -> \$REPO_PKG)"
    echo "${CHANNEL_MARKER} \$OLD_CHANNEL -> \$NEW_CHANNEL"
fi
""")

        return step.toString()
    }

    /** Points an apt-based server at the channel and refreshes only that channel's metadata. */
    private static String debRepoSteps() {
        return """
step "installing the Invary repo package"
# dpkg rather than apt: the repo package has no dependencies, and dpkg reinstalls the same
# version, where apt would report there is nothing to do and leave a re-run doing nothing
dpkg -i "\$RELPKG"

step "locating the Invary repository"
LIST_FILE=\$(dpkg -L "\$REPO_PKG" | grep '^/etc/apt/sources.list.d/.*\\.list\$' | head -n1)
if [ -z "\$LIST_FILE" ]; then
    echo "The \$REPO_PKG package placed no source list under /etc/apt/sources.list.d"
    exit 1
fi
echo "Using \$LIST_FILE"

# the source the repository is served from, which is how a version is told to have come from
# this channel rather than from wherever the machine had one before. The credentials live in
# /etc/apt/auth.conf.d and never appear here
LIST_URL=\$(awk '\$1 == "deb" { for (i = 2; i <= NF; i++) if (\$i ~ /^https?:\\/\\//) { sub(/\\/\$/, "", \$i); print \$i; exit } }' "\$LIST_FILE")

step "refreshing package metadata"
# this source alone. A bare apt-get update refreshes every repository the machine has, so an
# unrelated broken one fails the install and gets blamed on Invary
apt-get update \\
    -o Dir::Etc::sourcelist="\$LIST_FILE" \\
    -o Dir::Etc::sourceparts=/dev/null \\
    -o APT::Get::List-Cleanup=0
"""
    }

    /**
     * Installs the sensor on an apt-based server at the version the configured channel offers.
     *
     * Pinned to that version rather than left to resolve, because a machine moved from a
     * pre-release channel to the released one is already carrying something newer than the
     * released channel has: a bare install reports it is up to date and changes nothing, leaving
     * a machine whose repository says one thing and whose sensor says another.
     *
     * Nothing limits how far back that moves a sensor. A downgrade only ever happens on a
     * machine leaving a pre-release channel, and those are reached by Invary's own testers
     * rather than by customers.
     */
    private static String debInstallSteps() {
        return """
step "resolving the version the \$NEW_CHANNEL channel offers"
LISTING=\$(apt-cache madison ${SENSOR_PACKAGE} 2>/dev/null || true)

# the third column is the source a version comes from, so keeping only the rows this channel
# accounts for is what stops a sensor installed from elsewhere being taken as evidence for itself
OFFERED=\$(printf '%s\\n' "\$LISTING" \\
    | awk -F'|' -v url="\$LIST_URL" 'index(\$3, url) { gsub(/^[ \\t]+|[ \\t]+\$/, "", \$2); print \$2 }')
TARGET=\$(printf '%s\\n' "\$OFFERED" | head -n1)

step "installing the ${SENSOR_PACKAGE} package from the \$NEW_CHANNEL channel"
# apt-get install resolves against the lists already on disk and fetches nothing, so only
# apt-get update can fail on an unreachable repository and that one is already scoped above
if [ -n "\$TARGET" ]; then
    # --allow-downgrades because moving to the released channel from a pre-release one is a
    # downgrade, and it is the move the operator asked for by supplying that customer package
    DEBIAN_FRONTEND=noninteractive apt-get install -y --allow-downgrades ${SENSOR_PACKAGE}="\$TARGET"
else
    echo "Could not read which versions the \$NEW_CHANNEL channel offers; installing whichever is newest"
    DEBIAN_FRONTEND=noninteractive apt-get install -y ${SENSOR_PACKAGE}
fi
"""
    }

    /**
     * Installs the sensor on an rpm-based server and syncs it to the configured channel.
     *
     * `install` alone does nothing when the machine already carries something newer than the
     * channel offers, which is what a move from a pre-release channel to the released one looks
     * like; `distro-sync` moves the package in either direction. Both are needed: `distro-sync`
     * does nothing for a package that is not installed yet.
     *
     * Nothing limits how far back that moves a sensor, for the reason given on the deb path.
     */
    private static String rpmInstallSteps(String tool) {
        // skip_if_unavailable, because a transaction refreshes EVERY enabled repository before it
        // resolves anything, and one the customer cannot reach fails the whole thing with a
        // timeout that names their repository and blames this install.
        //
        // The repositories cannot simply be disabled instead: the sensor package depends on
        // glibc, elfutils-libelf and zlib, which come from the distribution's own. This makes an
        // unreachable one non-fatal rather than absent, so a genuinely missing dependency still
        // fails, and fails saying what is missing.
        String options = "-y --setopt=*.skip_if_unavailable=True"

        return """
step "installing the ${SENSOR_PACKAGE} package from the \$NEW_CHANNEL channel"
${tool} ${options} install ${SENSOR_PACKAGE}

step "synchronizing ${SENSOR_PACKAGE} with the \$NEW_CHANNEL channel"
# the install above is a no-op when the machine carries something newer than this channel has,
# which is what moving to the released channel from a pre-release one looks like. Only Invary
# repositories carry this package, so syncing it can only move it within this channel
${tool} ${options} distro-sync ${SENSOR_PACKAGE}
"""
    }

    /** Points a dnf or yum-based server at the channel, imports the key, and refreshes that repository. */
    private static String rpmRepoSteps(String tool) {
        // yum has no --repo for makecache; disabling everything and enabling one is the same
        // thing said the long way, and it works on both
        String makecache = (tool == 'dnf')
            ? "dnf -y -q makecache --refresh --repo \"\$REPO_ID\""
            : "yum -y -q --disablerepo='*' --enablerepo=\"\$REPO_ID\" makecache"

        return """
step "installing the Invary repo package"
# --replacepkgs so that re-running at the same version reinstalls rather than failing
rpm -Uvh --replacepkgs "\$RELPKG"

step "importing the Invary signing key"
# here rather than in the package's own scriptlet, which cannot do it: %post runs inside the
# transaction that is installing the package, and the rpmdb is locked for the duration
rpm --import ${RPM_KEY}
if ! rpm -qa 'gpg-pubkey*' --qf '%{VERSION}\\n' 2>/dev/null | grep -qix ${RPM_KEY_SHORT_ID}; then
    echo "The Invary signing key is not in the rpm keyring after importing ${RPM_KEY}"
    exit 1
fi

step "locating the Invary repository"
REPO_FILE=\$(rpm -ql "\$REPO_PKG" | grep '^/etc/yum.repos.d/.*\\.repo\$' | head -n1)
if [ -z "\$REPO_FILE" ]; then
    echo "The \$REPO_PKG package placed no repository file under /etc/yum.repos.d"
    exit 1
fi

REPO_ID=\$(sed -n 's/^\\[\\(.*\\)\\]\$/\\1/p' "\$REPO_FILE" | head -n1)
if [ -z "\$REPO_ID" ]; then
    echo "\$REPO_FILE declares no repository section"
    exit 1
fi
echo "Using \$REPO_FILE (\$REPO_ID)"

step "refreshing package metadata"
# this repository alone, and loudly when it fails. Refreshing every repository the machine has
# means an unrelated broken one fails the install, and a success says nothing about Invary.
# The -y is important: repo_gpgcheck makes the key import prompt, and a prompt that reads
# EOF fails as a bad signature rather than as a declined import
if ! ${makecache} >/dev/null 2>&1; then
    echo "Could not refresh metadata for \$REPO_ID:"
    ${makecache.replace(' -q', '')} || true
    exit 1
fi
"""
    }

    /**
     * Asserts the sensor now installed is a version the configured channel carries.
     *
     * Which channel a machine is configured for says nothing about where the sensor on it came
     * from. The install and the sync above move it onto the channel whenever the channel accounts
     * for a version at all, so reaching this step with a version it does not carry means the
     * sensor came from outside Invary's repositories entirely.
     *
     * A listing that cannot be read is reported rather than failed: an unparseable version list
     * is not evidence of a wrong version, and failing on it would break installs that worked.
     */
    private static String debMembershipStep() {
        return """
step "verifying the installed version comes from the \$NEW_CHANNEL channel"
INSTALLED=\$(dpkg-query -W -f='\${Version}' ${SENSOR_PACKAGE})

# an unreadable listing is not evidence of a wrong version, and failing on it would break
# installs that are fine. A listing that simply does not carry this version is different,
# and is the case this step exists for
if [ -z "\$LISTING" ]; then
    echo "Could not read which versions the \$NEW_CHANNEL channel offers; installed \$INSTALLED"
elif ! printf '%s\\n' "\$OFFERED" | grep -Fxq "\$INSTALLED"; then
    echo "The installed Invary Sensor is version \$INSTALLED, which the \$NEW_CHANNEL channel does not carry."
    echo "That channel offers: \$(printf '%s ' \$OFFERED)"
${unmanagedSensorNote()}
    exit 1
else
    echo "Installed \$INSTALLED from the \$NEW_CHANNEL channel"
fi
"""
    }

    /**
     * Says why a sensor the channel does not account for is there, which depends on what the
     * machine was carrying before.
     *
     * A machine this run moved between channels was on an Invary repository, so the version on it
     * is one of Invary's and the sync above should have moved it. A machine that carried no repo
     * package got its sensor from outside the repositories altogether.
     */
    private static String unmanagedSensorNote() {
        return """    if [ -n "\$OLD_REPO_PKG" ]; then
        echo "This machine was moved from the \$OLD_CHANNEL channel, and the sensor on it did not follow."
    else
        echo "The sensor on this machine came from somewhere else, so installing from this channel did nothing."
    fi"""
    }

    /** The same assertion for rpm, read out of the repository the repo package declared. */
    private static String rpmMembershipStep(String tool) {
        return """
step "verifying the installed version comes from the \$NEW_CHANNEL channel"
# EVR rather than VERSION-RELEASE: dnf prints an epoch when there is one and rpm's
# VERSION-RELEASE does not, and the two would then never compare equal
INSTALLED=\$(rpm -q --qf '%{EVR}' ${SENSOR_PACKAGE})
# list rather than repoquery, which needs yum-utils on the older hosts this supports. The -y
# is needed here for the same reason it is needed on the makecache above: repo_gpgcheck can
# still ask to import the key, and a prompt that reads EOF fails the listing entirely
LISTING=\$(${tool} -y -q --disablerepo='*' --enablerepo="\$REPO_ID" list --showduplicates ${SENSOR_PACKAGE} 2>/dev/null || true)

# the third column is where the version came from, and an installed one is marked with a
# leading @. Taking every version listed would count the installed package as evidence for
# itself, so a sensor installed from somewhere else would verify against its own entry. Only
# versions this repository accounts for are counted, installed or not
OFFERED=\$(printf '%s\\n' "\$LISTING" \\
    | awk -v repo="\$REPO_ID" '/^${SENSOR_PACKAGE}\\./ { origin = \$3; sub(/^@/, "", origin); if (origin == repo) print \$2 }')

if [ -z "\$LISTING" ]; then
    echo "Could not read which versions \$REPO_ID offers; installed \$INSTALLED"
elif ! printf '%s\\n' "\$OFFERED" | grep -Fxq "\$INSTALLED"; then
    echo "The installed Invary Sensor is version \$INSTALLED, which the \$NEW_CHANNEL channel does not carry."
    echo "That channel offers: \$(printf '%s ' \$OFFERED)"
${unmanagedSensorNote()}
    exit 1
else
    echo "Installed \$INSTALLED from the \$NEW_CHANNEL channel"
fi
"""
    }
}
