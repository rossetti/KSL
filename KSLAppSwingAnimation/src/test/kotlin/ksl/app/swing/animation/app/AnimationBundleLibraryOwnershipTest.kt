/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.app.swing.animation.app

import ksl.app.editor.BundleLibraryController
import ksl.examples.general.appsupport.LKInventoryModelBuilder
import ksl.examples.general.appsupport.MM1ModelBuilder
import ksl.examples.general.appsupport.ManifestBundleFixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  The Animation app closes the bundle classloaders it owns, and hands that ownership
 *  to the successor controller when the user switches models — the same contract the
 *  Single app follows, so that closing the outgoing controller never pulls the loaders
 *  out from under the model just opened.
 */
class AnimationBundleLibraryOwnershipTest {

    private val bundleId = "ksl.examples.animationswitch"

    @TempDir
    lateinit var bundleDir: Path

    private val twoModelJar: Path by lazy {
        ManifestBundleFixtures.assembleManifestBundle(
            bundleDir, "animationswitch", bundleId,
            MM1ModelBuilder::class.java, LKInventoryModelBuilder::class.java
        )
    }

    private var library: BundleLibraryController? = null
    private val openControllers = mutableListOf<AnimationAppController>()

    @AfterTest
    fun closeEverything() {
        openControllers.forEach { runCatching { it.close() } }
        openControllers.clear()
        runCatching { library?.close() }
        library = null
    }

    private fun loadedLibrary(): Pair<BundleLibraryController, List<String>> {
        val lib = BundleLibraryController().apply { loadJar(twoModelJar) }
        library = lib
        val models = lib.findBundle(bundleId)?.bundle?.models?.map { it.modelId } ?: emptyList()
        assertTrue(models.size >= 2, "fixture bundle should expose two models; was: $models")
        return lib to models
    }

    @Test
    @DisplayName("closing an owning controller closes the bundle library")
    fun closingAnOwningControllerClosesTheLibrary() {
        val (lib, models) = loadedLibrary()
        AnimationAppController.fromBundle("AnimSwitch", lib, bundleId, models[0]).close()

        assertTrue(lib.loadedBundles.value.isEmpty(), "close() should have closed the bundles.")
        assertNull(lib.bundleProvider.value, "close() should have dropped the provider.")
    }

    @Test
    @DisplayName("a controller that released ownership leaves the library open for its successor")
    fun releasingOwnershipKeepsTheLibraryOpen() {
        val (lib, models) = loadedLibrary()
        val outgoing = AnimationAppController.fromBundle("AnimSwitch", lib, bundleId, models[0])
        val successor = AnimationAppController.fromBundle("AnimSwitch", lib, bundleId, models[1])
        openControllers.add(successor)

        // What AnimationAppFrame.reopenWith does before disposing the old frame.
        outgoing.releaseBundleLibraryOwnership()
        outgoing.close()

        assertTrue(
            lib.loadedBundles.value.isNotEmpty(),
            "the successor's bundles must survive the outgoing controller's close."
        )
        assertNotNull(lib.bundleProvider.value, "the provider must survive the handoff.")
        assertNotNull(lib.bundleProvider.value!!.builderFor(bundleId, models[1]))
        assertEquals(models[1], successor.modelId)
    }

    @Test
    @DisplayName("a builder-mode controller owns no library and closes cleanly")
    fun builderModeOwnsNothing() {
        val (lib, _) = loadedLibrary()
        val builderMode = AnimationAppController(
            appName = "BuilderMode",
            modelBuilder = MM1ModelBuilder()
        )
        builderMode.close()
        assertTrue(
            lib.loadedBundles.value.isNotEmpty(),
            "a controller with no library of its own must not affect anyone else's."
        )
    }
}
