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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests for the per-result [ArtifactStore] (Phase A artifact substrate). */
class ArtifactStoreTest {

    @Test
    @DisplayName("list returns sorted refs with correct media types, including nested files")
    fun listsArtifactsWithMediaTypes(@TempDir root: Path) {
        val store = ArtifactStore(root)
        val dir = store.dirFor("res1")
        dir.resolve("welch.html").writeText("<html></html>")
        dir.resolve("plots").createDirectories()
        dir.resolve("plots").resolve("welch.png").writeText("PNGDATA")

        val refs = store.list("res1")
        assertEquals(listOf("plots/welch.png", "welch.html"), refs.map { it.name },
            "refs should be sorted by name with nested paths relativized")
        assertEquals("image/png", refs.first { it.name == "plots/welch.png" }.mediaType)
        assertEquals("text/html", refs.first { it.name == "welch.html" }.mediaType)
        assertTrue(refs.all { Files.exists(Path.of(it.path)) }, "each ref path must exist on disk")
    }

    @Test
    @DisplayName("list is empty for an unknown result")
    fun emptyForUnknownResult(@TempDir root: Path) {
        assertTrue(ArtifactStore(root).list("nope").isEmpty())
    }

    @Test
    @DisplayName("resolve returns the file for a valid name and round-trips a nested path")
    fun resolvesValidAndNested(@TempDir root: Path) {
        val store = ArtifactStore(root)
        val dir = store.dirFor("res1")
        dir.resolve("plots").createDirectories()
        dir.resolve("plots").resolve("x.png").writeText("bytes")

        val resolved = store.resolve("res1", "plots/x.png")
        assertTrue(resolved != null && Files.isRegularFile(resolved), "nested artifact must resolve")
    }

    @Test
    @DisplayName("resolve rejects path traversal and missing files")
    fun rejectsTraversalAndMissing(@TempDir root: Path) {
        val store = ArtifactStore(root)
        store.dirFor("res1")
        // Plant a secret beside the artifacts dir to prove it can't be escaped to.
        root.resolve("res1").resolve("result.json").writeText("secret")

        assertNull(store.resolve("res1", "../result.json"), "must not escape the artifacts dir")
        assertNull(store.resolve("res1", "missing.html"), "missing file resolves to null")
    }
}
