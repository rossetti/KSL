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

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The URL shape both transports hand back, and the traps in minting it. */
class ArtifactUrlsTest {

    @Test
    @DisplayName("a normal bind host and port become the base URL")
    fun plainHostAndPort() {
        assertEquals("http://127.0.0.1:3001", ArtifactUrls.baseUrl("127.0.0.1", 3001))
    }

    @Test
    @DisplayName("a wildcard bind address is reported as loopback, since it is not a reachable host")
    fun wildcardBecomesLoopback() {
        // A server bound to 0.0.0.0 listens everywhere, but "http://0.0.0.0:3001" is not a link
        // anyone can open. Loopback is right for the default single-user deployment.
        assertEquals("http://127.0.0.1:3001", ArtifactUrls.baseUrl("0.0.0.0", 3001))
        assertEquals("http://127.0.0.1:3001", ArtifactUrls.baseUrl("::", 3001))
        assertEquals("http://127.0.0.1:3001", ArtifactUrls.baseUrl("", 3001))
    }

    @Test
    @DisplayName("a bare IPv6 host is bracketed so the authority stays parseable")
    fun ipv6IsBracketed() {
        assertEquals("http://[fe80::1]:3001", ArtifactUrls.baseUrl("fe80::1", 3001))
        assertEquals("http://[::1]:3001", ArtifactUrls.baseUrl("[::1]", 3001))
    }

    @Test
    @DisplayName("no base URL means no link, rather than a wrong one")
    fun nullBaseYieldsNull() {
        assertNull(ArtifactUrls.forArtifact(null, "res1", "report.html"))
    }

    @Test
    @DisplayName("the artifact URL matches the route both servers expose")
    fun urlMatchesTheServedRoute() {
        assertEquals(
            "http://127.0.0.1:3001/results/res1/artifacts/report.html",
            ArtifactUrls.forArtifact("http://127.0.0.1:3001", "res1", "report.html"),
        )
    }

    @Test
    @DisplayName("a nested artifact name keeps its separators, so the route still matches")
    fun nestedNameKeepsItsSlashes() {
        // Encoding the whole name would turn plots/welch.png into plots%2Fwelch.png and the
        // {name...} tail parameter would never match it.
        assertEquals(
            "http://127.0.0.1:3001/results/res1/artifacts/plots/welch.png",
            ArtifactUrls.forArtifact("http://127.0.0.1:3001", "res1", "plots/welch.png"),
        )
    }

    @Test
    @DisplayName("a space in a name is percent-encoded, not turned into a plus")
    fun spacesEncodeAsPercent20() {
        val url = ArtifactUrls.forArtifact("http://127.0.0.1:3001", "res1", "MCB report.html")
        assertEquals("http://127.0.0.1:3001/results/res1/artifacts/MCB%20report.html", url)
        assertTrue("+" !in url!!, "form-encoding's + is invalid in a path segment")
    }

    @Test
    @DisplayName("ArtifactStore stamps a url on every listed artifact when a base is configured")
    fun storeStampsUrls() {
        val root = Files.createTempDirectory("artifact-url-store")
        val store = ArtifactStore(root, "http://127.0.0.1:3001")
        val dir = store.dirFor("res1")
        Files.writeString(dir.resolve("report.html"), "<html></html>")
        Files.createDirectories(dir.resolve("plots"))
        Files.writeString(dir.resolve("plots").resolve("welch.png"), "x")

        val refs = store.list("res1")
        assertEquals(2, refs.size)
        val report = refs.first { it.name == "report.html" }
        assertEquals("http://127.0.0.1:3001/results/res1/artifacts/report.html", report.url)
        val plot = refs.first { it.name == "plots/welch.png" }
        assertEquals("http://127.0.0.1:3001/results/res1/artifacts/plots/welch.png", plot.url)
        assertNotNull(report.path, "the on-disk path is still reported alongside the link")
    }

    @Test
    @DisplayName("a store with no base URL still lists artifacts, just without links")
    fun storeWithoutBaseOmitsUrls() {
        val root = Files.createTempDirectory("artifact-nourl-store")
        val store = ArtifactStore(root)
        Files.writeString(store.dirFor("res1").resolve("report.html"), "<html></html>")

        val refs = store.list("res1")
        assertEquals(1, refs.size)
        assertNull(refs.single().url, "no configured address means no link is invented")
    }
}
