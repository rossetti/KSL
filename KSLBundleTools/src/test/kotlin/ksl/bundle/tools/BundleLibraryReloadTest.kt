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
import ksl.bundle.tools.support.StubModelBuilder
import ksl.bundle.tools.support.TestBundleBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioral coverage for [BundleLibraryController.loadJar]'s reload path —
 * the fix for the stale-bundle-on-reload trap.  These tests assemble real
 * manifest bundle JARs (a plain [StubModelBuilder] JAR run through
 * `kslpkg assemble`) so a genuine [ksl.app.bundle.LoadedBundle] with a real
 * content hash is produced; the substrate unit test in `KSLApp` cannot do this
 * because `LoadedBundle` has an internal constructor.
 *
 * The author-iteration scenario being pinned: a JAR at a known path is loaded,
 * then *rebuilt in place* with the same `bundleId` but different content, and
 * re-loaded.  Before the fix the rebuilt JAR was silently discarded as a
 * duplicate; after it, the controller replaces the prior bundle and reports
 * [LoadBundleResult.Reloaded].
 */
class BundleLibraryReloadTest {

    private val sink = PrintStream(ByteArrayOutputStream())

    /** Assembles a manifest bundle (bundleId `test.stub`) at `<dir>/<name>.jar`.
     *  [version] varies the manifest content — and thus the JAR's SHA-256 — so
     *  reload tests can simulate a rebuilt-but-same-bundleId JAR.
     *
     *  Assert the assemble actually succeeded rather than discarding the result: a
     *  rebuild-in-place can fail (e.g. Windows refuses to replace a JAR the loader still
     *  holds open), and a swallowed failure would leave the prior JAR on disk and make a
     *  downstream assertion fail for the wrong, confusing reason. Fail here, at the cause. */
    private fun buildAt(dir: Path, name: String = "bundle", version: String = "1.0.0"): Path {
        val builders = TestBundleBuilder.buildWithoutServicesFile(
            dir, "$name-builders", listOf(StubModelBuilder::class.java)
        )
        val bundle = dir.resolve("$name.jar")
        val errBuf = ByteArrayOutputStream()
        val result = AssembleCommand.run(
            listOf(builders.toString(), "--id", "test.stub", "--version", version, "-o", bundle.toString(), "--force"),
            out = sink, err = PrintStream(errBuf)
        )
        assertEquals(CommandResult.Success, result, "assemble must succeed; stderr:\n$errBuf")
        return bundle
    }

    @Test
    fun `first load of a path reports Loaded and populates the library`(@TempDir dir: Path) {
        var callbacks = 0
        val c = BundleLibraryController(onBundlesChanged = { callbacks++ })
        val jar = buildAt(dir)

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
        val jar = buildAt(dir, version = "1.0.0")
        assertIs<LoadBundleResult.Loaded>(c.loadJar(jar))
        assertEquals(1, callbacks)

        // Rebuild the SAME path with different content (different SHA-256),
        // same bundleId "test.stub".
        val rebuilt = buildAt(dir, version = "2.0.0")
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
        val jar = buildAt(dir)
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

        val first = buildAt(dir, name = "first")
        assertIs<LoadBundleResult.Loaded>(c.loadJar(first))

        // A second, different file declaring the same bundleId "test.stub".
        val second = buildAt(dir, name = "second")
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
        val jar = buildAt(dir, version = "1.0.0")
        c.loadJar(jar)
        c.loadJar(buildAt(dir, version = "2.0.0"))   // retires the v1 bundle

        c.close()   // must close live + retired without throwing

        assertTrue(c.loadedBundles.value.isEmpty(),
            "close must reset loadedBundles to empty.")
        assertNull(c.bundleProvider.value,
            "close must reset bundleProvider to null.")
        c.close()   // idempotent
    }
}
