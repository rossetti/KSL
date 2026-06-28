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

package ksl.app.editor

import ksl.examples.general.appsupport.LKInventoryModelBuilder
import ksl.examples.general.appsupport.MM1ModelBuilder
import ksl.examples.general.appsupport.ManifestBundleFixtures
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Substrate tests for [BundleLibraryController] — the shared
 *  bundle-library bookkeeping (loaded list, provider adapter,
 *  jar-load + de-duplication) that Scenario / Experiment / Simopt
 *  all compose after Phase E.5.8 decomposition.
 *
 *  Light-weight test surface: `LoadedBundle` has an internal
 *  constructor, so the with-bundles paths (provider becomes non-null,
 *  findBundle hits, the callback fires) are exercised by assembling a
 *  real fixture bundle JAR (via `ManifestBundleFixtures`) and loading
 *  it through `loadJar` — never by constructing a `LoadedBundle`
 *  directly.
 */
class BundleLibraryControllerTest {

    // ── Initial state ────────────────────────────────────────────────────

    @Test
    fun `fresh controller has empty loadedBundles and null bundleProvider`() {
        val c = BundleLibraryController()
        assertTrue(c.loadedBundles.value.isEmpty(),
            "Fresh controller must have empty loadedBundles.")
        assertNull(c.bundleProvider.value,
            "Fresh controller must have null bundleProvider.")
    }

    // ── loadJar (success path) ───────────────────────────────────────────

    @Test
    fun `loadJar of a real bundle fires the callback once and populates the provider`(@TempDir dir: Path) {
        val jar = ManifestBundleFixtures.assembleManifestBundle(
            dir, "mm1", "ksl.examples.mm1", MM1ModelBuilder::class.java
        )
        var callbackCount = 0
        val c = BundleLibraryController(onBundlesChanged = { callbackCount++ })
        val result = c.loadJar(jar)
        assertTrue(result is BundleLibraryController.LoadBundleResult.Loaded,
            "Loading a real bundle JAR must yield Loaded; got $result")
        assertTrue(c.loadedBundles.value.isNotEmpty(),
            "loadedBundles must be populated after a successful load.")
        assertNotNull(c.bundleProvider.value,
            "Non-empty loadedBundles must coincide with non-null bundleProvider.")
        assertEquals(1, callbackCount,
            "onBundlesChanged must fire exactly once on a successful load.")
    }

    // ── loadJar (error path) ─────────────────────────────────────────────

    @Test
    fun `loadJar with a nonexistent path returns Failed with a non-null reason`() {
        var callbackCount = 0
        val c = BundleLibraryController(onBundlesChanged = { callbackCount++ })
        val result = c.loadJar(Path.of("/nonexistent/path/does-not-exist.jar"))
        assertTrue(result is BundleLibraryController.LoadBundleResult.Failed,
            "Loading a nonexistent JAR must yield Failed.")
        val failed = result as BundleLibraryController.LoadBundleResult.Failed
        assertTrue(failed.reason.isNotBlank(),
            "Failed.reason must carry a non-blank message.")
        assertEquals(0, callbackCount,
            "onBundlesChanged must NOT fire when loadJar fails.")
        assertTrue(c.loadedBundles.value.isEmpty(),
            "Failed loadJar must not mutate loadedBundles.")
    }

    @Test
    fun `loadJar Failed does not flip bundleProvider`() {
        val c = BundleLibraryController()
        c.loadJar(Path.of("/nonexistent/path/does-not-exist.jar"))
        assertNull(c.bundleProvider.value,
            "Failed loadJar must not flip bundleProvider.")
    }

    // ── findBundle ───────────────────────────────────────────────────────

    @Test
    fun `findBundle returns null when the controller is empty`() {
        val c = BundleLibraryController()
        assertNull(c.findBundle("nonexistent"))
    }

    @Test
    fun `findBundle returns the loaded bundle by id and null for others`(@TempDir dir: Path) {
        // Even with a bundle loaded, findBundle is selective: it returns
        // the loaded bundle for its id and null for any other.
        val jar = ManifestBundleFixtures.assembleManifestBundle(
            dir, "mm1", "ksl.examples.mm1", MM1ModelBuilder::class.java
        )
        val c = BundleLibraryController().apply { loadJar(jar) }
        assertNotNull(c.findBundle("ksl.examples.mm1"),
            "findBundle must return the bundle that was loaded.")
        assertNull(c.findBundle("definitely-not-a-real-bundleId-xyz123"),
            "findBundle must return null for an unknown bundleId even when bundles are loaded.")
    }

    // ── close ────────────────────────────────────────────────────────────

    @Test
    fun `close is safe on an empty controller`() {
        val c = BundleLibraryController()
        c.close()  // must not throw
        assertTrue(c.loadedBundles.value.isEmpty(),
            "close must not mutate loadedBundles.")
    }

    @Test
    fun `close is safe to call twice on an empty controller`() {
        val c = BundleLibraryController()
        c.close()
        c.close()  // must not throw
    }

    // ── onBundlesChanged callback semantics ──────────────────────────────

    @Test
    fun `onBundlesChanged callback does not fire on Failed loadJar`() {
        var callbackCount = 0
        val c = BundleLibraryController(onBundlesChanged = { callbackCount++ })
        c.loadJar(Path.of("/nonexistent/does-not-exist.jar"))
        assertEquals(0, callbackCount,
            "onBundlesChanged must not fire when loadJar returns Failed.")
    }

    @Test
    fun `default onBundlesChanged is a no-op when omitted`() {
        // Verifies the default lambda doesn't throw — the default
        // controller construction path is exercised by Scenario.
        val c = BundleLibraryController()
        c.loadJar(Path.of("/nonexistent/does-not-exist.jar"))  // must not throw without a callback
    }

    // ── discoverFromDirectories ──────────────────────────────────────────

    @Test
    fun `discoverFromDirectories loads bundles from every directory`(@TempDir root: Path) {
        val appDir = Files.createDirectories(root.resolve("app"))
        val sharedDir = Files.createDirectories(root.resolve("shared"))
        ManifestBundleFixtures.assembleManifestBundle(appDir, "mm1", "ksl.examples.mm1", MM1ModelBuilder::class.java)
        ManifestBundleFixtures.assembleManifestBundle(
            sharedDir, "lk", "ksl.examples.lk-inventory", LKInventoryModelBuilder::class.java
        )
        val c = BundleLibraryController()
        c.discoverFromDirectories(appDir, sharedDir)
        val ids = c.loadedBundles.value.map { it.bundle.bundleId }.toSet()
        assertTrue(
            ids.containsAll(listOf("ksl.examples.mm1", "ksl.examples.lk-inventory")),
            "both directories' bundles should be discovered; got $ids"
        )
    }

    @Test
    fun `discoverFromDirectories gives earlier directories precedence and silently drops a byte-identical copy`(@TempDir root: Path) {
        val appDir = Files.createDirectories(root.resolve("app"))
        val sharedDir = Files.createDirectories(root.resolve("shared"))
        // The exact same bundle JAR in both the app-specific and shared layers.
        val jar = ManifestBundleFixtures.assembleManifestBundle(appDir, "mm1", "ksl.examples.mm1", MM1ModelBuilder::class.java)
        Files.copy(jar, sharedDir.resolve(jar.fileName))
        val c = BundleLibraryController()
        c.discoverFromDirectories(appDir, sharedDir)   // app directory scanned first
        val loaded = c.loadedBundles.value.filter { it.bundle.bundleId == "ksl.examples.mm1" }
        assertEquals(1, loaded.size, "a duplicate bundleId across directories must load once (first-wins)")
        assertTrue(
            loaded.single().sourceJar?.startsWith(appDir) == true,
            "the earlier (app) directory's copy must win; got ${loaded.single().sourceJar}"
        )
        assertTrue(
            c.ignoredCopies.value.isEmpty(),
            "a byte-identical duplicate is dropped silently, not disclosed; got ${c.ignoredCopies.value}"
        )
    }

    @Test
    fun `discoverFromDirectories discloses a different-content same-bundleId copy as a conflict`(@TempDir root: Path) {
        val appDir = Files.createDirectories(root.resolve("app"))
        val sharedDir = Files.createDirectories(root.resolve("shared"))
        // Same bundleId, DIFFERENT content (different model builders → different bytes).
        ManifestBundleFixtures.assembleManifestBundle(appDir, "a", "ksl.test.dup", MM1ModelBuilder::class.java)
        ManifestBundleFixtures.assembleManifestBundle(sharedDir, "b", "ksl.test.dup", LKInventoryModelBuilder::class.java)
        val c = BundleLibraryController()
        c.discoverFromDirectories(appDir, sharedDir)
        assertEquals(1, c.loadedBundles.value.size, "precedence keeps one copy per bundleId")
        val ignored = c.ignoredCopies.value
        assertEquals(1, ignored.size, "the different-content shadowed copy must be disclosed; got $ignored")
        assertEquals("ksl.test.dup", ignored.single().bundleId)
    }

    // ── LoadBundleResult shape ───────────────────────────────────────────

    @Test
    fun `LoadBundleResult has Loaded Reloaded AlreadyLoaded NoBundles Failed and Rejected variants`() {
        // Pins the public sealed-class shape — each frame's when
        // clause depends on these six variants existing.
        val variants: List<BundleLibraryController.LoadBundleResult> = listOf(
            BundleLibraryController.LoadBundleResult.Loaded(listOf("bundleA", "bundleB")),
            BundleLibraryController.LoadBundleResult.Reloaded(listOf("bundleA")),
            BundleLibraryController.LoadBundleResult.AlreadyLoaded(listOf("bundleA")),
            BundleLibraryController.LoadBundleResult.NoBundles,
            BundleLibraryController.LoadBundleResult.Failed("bad jar"),
            BundleLibraryController.LoadBundleResult.Rejected("not a KSL bundle"),
        )
        assertEquals(6, variants.size)
        // Type checks
        assertTrue(variants[0] is BundleLibraryController.LoadBundleResult.Loaded)
        assertTrue(variants[1] is BundleLibraryController.LoadBundleResult.Reloaded)
        assertTrue(variants[2] is BundleLibraryController.LoadBundleResult.AlreadyLoaded)
        assertTrue(variants[3] is BundleLibraryController.LoadBundleResult.NoBundles)
        assertTrue(variants[4] is BundleLibraryController.LoadBundleResult.Failed)
        assertTrue(variants[5] is BundleLibraryController.LoadBundleResult.Rejected)
        // Payload accessors
        val loaded = variants[0] as BundleLibraryController.LoadBundleResult.Loaded
        assertEquals(listOf("bundleA", "bundleB"), loaded.newBundleIds)
        val reloaded = variants[1] as BundleLibraryController.LoadBundleResult.Reloaded
        assertEquals(listOf("bundleA"), reloaded.bundleIds)
        val already = variants[2] as BundleLibraryController.LoadBundleResult.AlreadyLoaded
        assertEquals(listOf("bundleA"), already.bundleIds)
        val failed = variants[4] as BundleLibraryController.LoadBundleResult.Failed
        assertEquals("bad jar", failed.reason)
        val rejected = variants[5] as BundleLibraryController.LoadBundleResult.Rejected
        assertEquals("not a KSL bundle", rejected.reason)
    }

    @Test
    fun `LoadBundleResult Loaded carries an empty list when constructed empty`() {
        val loaded = BundleLibraryController.LoadBundleResult.Loaded(emptyList())
        assertTrue(loaded.newBundleIds.isEmpty(),
            "Loaded with an empty bundle-id list is a valid construction.")
        assertNotNull(loaded)
    }
}
