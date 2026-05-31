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

import org.junit.jupiter.api.Test
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
 *  Light-weight test surface: [ksl.app.bundle.LoadedBundle] has an
 *  internal constructor, so the with-bundles paths
 *  (de-duplication, provider becomes non-null) are exercised
 *  indirectly by the existing per-app test suites which load real
 *  fixture JARs.  These tests pin the substrate contract that does
 *  not require constructing a [LoadedBundle].
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

    // ── discoverFromClasspath ────────────────────────────────────────────

    @Test
    fun `discoverFromClasspath does not throw and fires callback iff bundles found`() {
        // The KSLTesting classpath may or may not carry KSLModelBundle
        // SPI registrations depending on which fixture modules are
        // present; the substrate contract is the same either way:
        //   - if the classpath probe returns empty, NEITHER flow
        //     mutates AND onBundlesChanged does NOT fire,
        //   - if it returns non-empty, loadedBundles becomes that
        //     list, bundleProvider becomes non-null, AND
        //     onBundlesChanged fires exactly once.
        // Verify the iff coupling without assuming which case we're in.
        var callbackCount = 0
        val c = BundleLibraryController(onBundlesChanged = { callbackCount++ })
        c.discoverFromClasspath()
        val discoveredAnything = c.loadedBundles.value.isNotEmpty()
        if (discoveredAnything) {
            assertNotNull(c.bundleProvider.value,
                "Non-empty loadedBundles must coincide with non-null bundleProvider.")
            assertEquals(1, callbackCount,
                "onBundlesChanged must fire exactly once when classpath bundles are discovered.")
        } else {
            assertNull(c.bundleProvider.value,
                "Empty loadedBundles must coincide with null bundleProvider.")
            assertEquals(0, callbackCount,
                "onBundlesChanged must NOT fire when classpath probe finds nothing.")
        }
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
    fun `findBundle returns null for a bundleId that does not match`() {
        // Same as the empty case but the assertion intent is
        // different: even after discoverFromClasspath has run (no-op
        // here on the test classpath), lookups for unknown ids
        // return null.
        val c = BundleLibraryController()
        c.discoverFromClasspath()
        assertNull(c.findBundle("definitely-not-a-real-bundleId-xyz123"))
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
        c.discoverFromClasspath()  // must not throw even without a callback
        c.loadJar(Path.of("/nonexistent/does-not-exist.jar"))  // same
    }

    // ── LoadBundleResult shape ───────────────────────────────────────────

    @Test
    fun `LoadBundleResult has Loaded Reloaded AlreadyLoaded NoBundles and Failed variants`() {
        // Pins the public sealed-class shape — each frame's when
        // clause depends on these five variants existing.
        val variants: List<BundleLibraryController.LoadBundleResult> = listOf(
            BundleLibraryController.LoadBundleResult.Loaded(listOf("bundleA", "bundleB")),
            BundleLibraryController.LoadBundleResult.Reloaded(listOf("bundleA")),
            BundleLibraryController.LoadBundleResult.AlreadyLoaded(listOf("bundleA")),
            BundleLibraryController.LoadBundleResult.NoBundles,
            BundleLibraryController.LoadBundleResult.Failed("bad jar"),
        )
        assertEquals(5, variants.size)
        // Type checks
        assertTrue(variants[0] is BundleLibraryController.LoadBundleResult.Loaded)
        assertTrue(variants[1] is BundleLibraryController.LoadBundleResult.Reloaded)
        assertTrue(variants[2] is BundleLibraryController.LoadBundleResult.AlreadyLoaded)
        assertTrue(variants[3] is BundleLibraryController.LoadBundleResult.NoBundles)
        assertTrue(variants[4] is BundleLibraryController.LoadBundleResult.Failed)
        // Payload accessors
        val loaded = variants[0] as BundleLibraryController.LoadBundleResult.Loaded
        assertEquals(listOf("bundleA", "bundleB"), loaded.newBundleIds)
        val reloaded = variants[1] as BundleLibraryController.LoadBundleResult.Reloaded
        assertEquals(listOf("bundleA"), reloaded.bundleIds)
        val already = variants[2] as BundleLibraryController.LoadBundleResult.AlreadyLoaded
        assertEquals(listOf("bundleA"), already.bundleIds)
        val failed = variants[4] as BundleLibraryController.LoadBundleResult.Failed
        assertEquals("bad jar", failed.reason)
    }

    @Test
    fun `LoadBundleResult Loaded carries an empty list when constructed empty`() {
        val loaded = BundleLibraryController.LoadBundleResult.Loaded(emptyList())
        assertTrue(loaded.newBundleIds.isEmpty(),
            "Loaded with an empty bundle-id list is a valid construction.")
        assertNotNull(loaded)
    }
}
