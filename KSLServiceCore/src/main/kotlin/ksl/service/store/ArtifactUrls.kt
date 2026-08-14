/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.service.store

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Mints the HTTP download URL for a stored artifact, so a tool result can hand back a link the
 * user can open instead of a machine-specific filesystem path.
 *
 * URL minting lives at the transport layer, not in `ArtifactStore`: the store knows where files
 * are on disk, while only the serving process knows the address it is reachable at. Both the MCP
 * suite and the REST server route through here so the two transports agree on one URL shape:
 * `<base>/results/<resultId>/artifacts/<name>`. That is the route `KSLServerRest` has always
 * served (its refs simply never carried a link to it), so REST needs no new routing and the suite
 * mounts a matching one. The name segment is resolved by `ArtifactStore.resolve`, which confines
 * it to the result's artifacts directory.
 */
object ArtifactUrls {

    /**
     * The base URL a client should use to reach a server bound to [bindHost] on [port].
     *
     * A wildcard bind address is not a reachable host: a server bound to `0.0.0.0` (or `::`) is
     * listening everywhere, but that string is useless in a link, so it is reported as loopback —
     * which is correct for the default single-user deployment. A hosted deployment that is
     * genuinely reachable under some other name should set `KSL_PUBLIC_BASE_URL`, which wins over
     * everything and is returned as given (minus any trailing slash).
     */
    fun baseUrl(bindHost: String, port: Int): String {
        System.getenv(PUBLIC_BASE_URL)?.takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }
        val host = when (bindHost) {
            "0.0.0.0", "::", "[::]", "" -> "127.0.0.1"
            else -> bindHost
        }
        // A bare IPv6 literal has to be bracketed in a URL authority.
        val authority = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "http://$authority:$port"
    }

    /**
     * The URL for one artifact, or `null` when [base] is null (no address configured — callers then
     * fall back to reporting the path alone).
     *
     * [name] may be a nested relative path (`plots/welch.png`). Each segment is encoded
     * individually so the separators survive: encoding the whole name would turn its slashes into
     * `%2F` and the route would never match. `URLEncoder` is form-encoding, so its `+` for space is
     * corrected to `%20`, which is what a path segment requires.
     */
    fun forArtifact(base: String?, resultId: String, name: String): String? {
        if (base == null) return null
        val encodedName = name.split('/').joinToString("/") { encodeSegment(it) }
        return "$base/results/${encodeSegment(resultId)}/artifacts/$encodedName"
    }

    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")

    /** Environment variable naming the externally reachable base URL of a hosted deployment. */
    const val PUBLIC_BASE_URL = "KSL_PUBLIC_BASE_URL"
}
