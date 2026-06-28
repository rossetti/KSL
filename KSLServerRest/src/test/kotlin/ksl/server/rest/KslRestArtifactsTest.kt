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

package ksl.server.rest

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import ksl.service.store.ArtifactStore
import ksl.service.store.ResultStore
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Endpoint tests for the Phase A artifact surface: list + download over REST.
 * A single temp directory backs both the [ResultStore] and the [ArtifactStore]
 * so artifacts seeded on disk under `<root>/<resultId>/artifacts/` are visible
 * to the service, exercising the discover -> list -> download path end to end.
 */
class KslRestArtifactsTest {

    @Test
    @DisplayName("lists seeded artifacts and downloads them, including a nested plot path")
    fun listsAndDownloadsArtifacts() = testApplication {
        val root = Files.createTempDirectory("rest-artifacts")
        val registry = TestBundles.registry()
        val service = KslRestService(
            registry,
            resultStore = ResultStore(root),
            artifactStore = ArtifactStore(root),
        )
        // Seed two artifacts for resultId "res1" directly on disk.
        val artifacts = root.resolve("res1").resolve("artifacts")
        artifacts.createDirectories()
        artifacts.resolve("welch.html").writeText("<html>welch</html>")
        artifacts.resolve("plots").createDirectories()
        artifacts.resolve("plots").resolve("welch.png").writeText("PNGBYTES")

        application { kslRestModule(service) }
        try {
            val list = client.get("/results/res1/artifacts").bodyAsText()
            assertTrue(list.contains("welch.html"), "list must name welch.html; was: $list")
            assertTrue(list.contains("plots/welch.png"), "list must name the nested plot; was: $list")
            assertTrue(list.contains("text/html") && list.contains("image/png"),
                "list must carry media types; was: $list")

            val html = client.get("/results/res1/artifacts/welch.html")
            assertEquals(HttpStatusCode.OK, html.status)
            assertEquals("<html>welch</html>", html.bodyAsText())

            // Tailcard route serves the nested file.
            val png = client.get("/results/res1/artifacts/plots/welch.png")
            assertEquals(HttpStatusCode.OK, png.status)
            assertEquals("PNGBYTES", png.bodyAsText())
        } finally {
            service.close()
            registry.close()
        }
    }

    @Test
    @DisplayName("unknown artifact and unknown result return empty list / 404")
    fun unknownArtifactIs404() = testApplication {
        val root = Files.createTempDirectory("rest-artifacts-empty")
        val registry = TestBundles.registry()
        val service = KslRestService(
            registry,
            resultStore = ResultStore(root),
            artifactStore = ArtifactStore(root),
        )
        application { kslRestModule(service) }
        try {
            assertEquals("[]", client.get("/results/none/artifacts").bodyAsText().trim(),
                "unknown result lists no artifacts")
            assertEquals(HttpStatusCode.NotFound, client.get("/results/none/artifacts/missing.html").status)
        } finally {
            service.close()
            registry.close()
        }
    }
}
