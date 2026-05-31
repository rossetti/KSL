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

package ksl.bundle.tools

import ksl.app.editor.BundleLibraryController
import ksl.app.editor.BundleLibraryController.LoadBundleResult
import ksl.bundle.tools.support.StubBundle
import ksl.bundle.tools.support.TestBundleBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioral coverage for [BundleLibraryController.loadJar]'s reload path —
 * the fix for the stale-bundle-on-reload trap.  These tests build real JARs
 * (via [TestBundleBuilder] + [StubBundle]) so a genuine [ksl.app.bundle.LoadedBundle]
 * with a real content hash is produced; the substrate unit test in
 * `KSLTesting` cannot do this because `LoadedBundle` has an internal
 * constructor.
 *
 * The author-iteration scenario being pinned: a JAR at a known path is loaded,
 * then *rebuilt in place* with the same `bundleId` but different content, and
 * re-loaded.  Before the fix the rebuilt JAR was silently discarded as a
 * duplicate; after it, the controller replaces the prior bundle and reports
 * [LoadBundleResult.Reloaded].
 */
class BundleLibraryReloadTest {

    /** Builds a JAR at `<dir>/bundle.jar`, optionally with a content marker. */
    private fun buildAt(dir: Path, marker: String?): Path =
        TestBundleBuilder.build(
            dir, "bundle", listOf(StubBundle::class.java),
            extraEntries = if (marker == null) emptyMap()
                           else mapOf("content-marker.txt" to marker.toByteArray())
        )

    @Test
    fun `first load of a path reports Loaded and populates the library`(@TempDir dir: Path) {
        var callbacks = 0
        val c = BundleLibraryController(onBundlesChanged = { callbacks++ })
        val jar = buildAt(dir, marker = null)

        val outcome = c.loadJar(jar)

        assertIs<LoadBundleResult.Loaded>(outcome)
        assertEquals(listOf("test.stub"), outcome.newBundleIds)
        assertEquals(1, c.loadedBundles.value.size)
        assertNotNull(c.bundleProvider.value)
        assertEquals(1, callbacks, "onBundlesChanged must fire once on a first Loaded.")
        c.close()
    }

    @Test
    fun `re-loading the same path with changed content reports Reloaded and replaces in place`(@TempDir dir: Path) {
        var callbacks = 0
        val c = BundleLibraryController(onBundlesChanged = { callbacks++ })

        // v1 at the path.
        val jar = buildAt(dir, marker = "v1")
        assertIs<LoadBundleResult.Loaded>(c.loadJar(jar))
        assertEquals(1, callbacks)

        // Rebuild the SAME path with different content (different SHA-256),
        // same StubBundle (same bundleId "test.stub").
        val rebuilt = buildAt(dir, marker = "v2-different-bytes")
        assertEquals(jar, rebuilt, "Rebuild must target the same path to be a reload.")

        val outcome = c.loadJar(rebuilt)

        assertIs<LoadBundleResult.Reloaded>(outcome)
        assertEquals(listOf("test.stub"), outcome.bundleIds)
        assertEquals(1, c.loadedBundles.value.size,
            "Reload must replace in place, not append a duplicate.")
        assertNotNull(c.bundleProvider.value)
        assertEquals(2, callbacks, "onBundlesChanged must fire on a Reloaded.")
        c.close()
    }

    @Test
    fun `re-loading the same path with identical content reports AlreadyLoaded and does not refire`(@TempDir dir: Path) {
        var callbacks = 0
        val c = BundleLibraryController(onBundlesChanged = { callbacks++ })
        val jar = buildAt(dir, marker = "stable")
        assertIs<LoadBundleResult.Loaded>(c.loadJar(jar))
        assertEquals(1, callbacks)

        // Same path, unchanged bytes (same SHA-256).
        val outcome = c.loadJar(jar)

        assertIs<LoadBundleResult.AlreadyLoaded>(outcome)
        assertEquals(listOf("test.stub"), outcome.bundleIds)
        assertEquals(1, c.loadedBundles.value.size)
        assertEquals(1, callbacks,
            "AlreadyLoaded must NOT fire onBundlesChanged — nothing changed.")
        c.close()
    }

    @Test
    fun `loading a different path whose bundleId is already loaded reports AlreadyLoaded`(@TempDir dir: Path) {
        var callbacks = 0
        val c = BundleLibraryController(onBundlesChanged = { callbacks++ })

        val first = TestBundleBuilder.build(dir, "first", listOf(StubBundle::class.java))
        assertIs<LoadBundleResult.Loaded>(c.loadJar(first))

        // A second, different file declaring the same bundleId "test.stub".
        val second = TestBundleBuilder.build(dir, "second", listOf(StubBundle::class.java))
        val outcome = c.loadJar(second)

        assertIs<LoadBundleResult.AlreadyLoaded>(outcome)
        assertEquals(1, c.loadedBundles.value.size,
            "A same-bundleId JAR from a different path must not be added.")
        assertEquals(1, callbacks, "AlreadyLoaded must not refire the callback.")
        c.close()
    }

    @Test
    fun `close drains the library and the retired set without throwing`(@TempDir dir: Path) {
        val c = BundleLibraryController()
        val jar = buildAt(dir, marker = "v1")
        c.loadJar(jar)
        c.loadJar(buildAt(dir, marker = "v2"))   // retires the v1 bundle

        c.close()   // must close live + retired without throwing

        assertTrue(c.loadedBundles.value.isEmpty(),
            "close must reset loadedBundles to empty.")
        assertNull(c.bundleProvider.value,
            "close must reset bundleProvider to null.")
        c.close()   // idempotent
    }
}
