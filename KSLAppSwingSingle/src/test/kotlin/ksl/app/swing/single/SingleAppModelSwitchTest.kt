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

package ksl.app.swing.single

import ksl.app.config.ModelReference
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.app.editor.BundleLibraryController
import ksl.examples.general.appsupport.LKInventoryModelBuilder
import ksl.examples.general.appsupport.MM1ModelBuilder
import ksl.examples.general.appsupport.ManifestBundleFixtures
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.GraphicsEnvironment
import java.nio.file.Path
import javax.swing.JMenu
import javax.swing.JMenuBar
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Covers in-session model switching — *Bundles → Open Model…* — and the
 *  bundle-library ownership contract that makes it safe.
 *
 *  The switch replaces the controller and its frame rather than rebinding in
 *  place, so the two things that can go wrong are (a) the successor being built
 *  against the wrong model and (b) the outgoing controller closing the
 *  classloaders the successor now depends on.  Both are pinned here.
 */
class SingleAppModelSwitchTest {

    private val bundleId = "ksl.examples.switchtest"

    @TempDir
    lateinit var bundleDir: Path

    /** A two-model bundle so a switch has somewhere to go. */
    private val twoModelJar: Path by lazy {
        ManifestBundleFixtures.assembleManifestBundle(
            bundleDir, "switchtest", bundleId,
            MM1ModelBuilder::class.java, LKInventoryModelBuilder::class.java
        )
    }

    private var library: BundleLibraryController? = null
    private val openControllers = mutableListOf<SingleAppController>()

    @AfterTest
    fun closeEverything() {
        openControllers.forEach { runCatching { it.close() } }
        openControllers.clear()
        runCatching { library?.close() }
        library = null
    }

    /** A loaded library over [twoModelJar], plus its two model ids in bundle order. */
    private fun loadedLibrary(): Pair<BundleLibraryController, List<String>> {
        val lib = BundleLibraryController().apply { loadJar(twoModelJar) }
        library = lib
        val models = lib.findBundle(bundleId)?.bundle?.models?.map { it.modelId } ?: emptyList()
        assertTrue(models.size >= 2, "fixture bundle should expose two models; was: $models")
        return lib to models
    }

    private fun track(controller: SingleAppController): SingleAppController {
        openControllers.add(controller)
        return controller
    }

    @Test
    @DisplayName("fromBundle binds the requested model and records it as the source reference")
    fun fromBundleBindsTheRequestedModel() {
        val (lib, models) = loadedLibrary()
        val first = track(SingleAppController.fromBundle("SwitchApp", lib, bundleId, models[0]))
        val second = track(SingleAppController.fromBundle("SwitchApp", lib, bundleId, models[1]))

        assertEquals(ModelReference.ByBundleAndModelId(bundleId, models[0]), first.sourceRef)
        assertEquals(ModelReference.ByBundleAndModelId(bundleId, models[1]), second.sourceRef)
        // The probe ran against different builders, so the two controllers describe
        // different models — this is what re-shapes the tabs after a switch.
        assertNotNull(first.modelName)
        assertTrue(
            first.modelName != second.modelName,
            "two different bundle models should probe to different model names; " +
                "was '${first.modelName}' twice"
        )
    }

    @Test
    @DisplayName("fromBundle fails loudly when the library has no provider")
    fun fromBundleFailsWithoutAProvider() {
        val empty = BundleLibraryController()
        library = empty
        assertFailsWith<IllegalStateException> {
            SingleAppController.fromBundle("SwitchApp", empty, bundleId, "anything")
        }
    }

    @Test
    @DisplayName("closing an owning controller closes the bundle library")
    fun closingAnOwningControllerClosesTheLibrary() {
        val (lib, models) = loadedLibrary()
        val controller = SingleAppController.fromBundle("SwitchApp", lib, bundleId, models[0])
        controller.close()

        assertTrue(lib.loadedBundles.value.isEmpty(), "close() should have closed the bundles.")
        assertNull(lib.bundleProvider.value, "close() should have dropped the provider.")
    }

    @Test
    @DisplayName("a controller that released ownership leaves the library open for its successor")
    fun releasingOwnershipKeepsTheLibraryOpen() {
        val (lib, models) = loadedLibrary()
        val outgoing = SingleAppController.fromBundle("SwitchApp", lib, bundleId, models[0])
        val successor = track(SingleAppController.fromBundle("SwitchApp", lib, bundleId, models[1]))

        // What SingleAppFrame.reopenWith does before disposing the old frame.
        outgoing.releaseBundleLibraryOwnership()
        outgoing.close()

        assertTrue(
            lib.loadedBundles.value.isNotEmpty(),
            "the successor's bundles must survive the outgoing controller's close."
        )
        assertNotNull(lib.bundleProvider.value, "the provider must survive the handoff.")
        // The successor can still resolve builders — i.e. its classloaders are alive,
        // which is what a post-switch Simulate depends on.
        assertNotNull(lib.bundleProvider.value!!.builderFor(bundleId, models[1]))
        assertEquals(ModelReference.ByBundleAndModelId(bundleId, models[1]), successor.sourceRef)
    }

    @Test
    @DisplayName("a configuration for another loaded model reports DifferentModel instead of applying")
    fun configurationForAnotherModelReportsDifferentModel() {
        val (lib, models) = loadedLibrary()
        val controller = track(SingleAppController.fromBundle("SwitchApp", lib, bundleId, models[0]))
        controller.updateRunOverride { it.copy(numberOfReplications = 7) }

        val otherModelConfig = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "SwitchApp",
                    modelReference = ModelReference.ByBundleAndModelId(bundleId, models[1]),
                    runOverrides = ksl.app.config.ExperimentRunOverrides(numberOfReplications = 99)
                )
            )
        )
        val outcome = controller.loadConfiguration(otherModelConfig)

        assertTrue(
            outcome is SingleAppController.LoadResult.DifferentModel,
            "a config for another loaded model must offer the switch, not load; was: $outcome"
        )
        outcome as SingleAppController.LoadResult.DifferentModel
        assertEquals(bundleId, outcome.bundleId)
        assertEquals(models[1], outcome.modelId)
        assertEquals(
            7, controller.runOverrides.value.numberOfReplications,
            "DifferentModel must leave the current document untouched."
        )
    }

    @Test
    @DisplayName("bundle mode offers Open Model, builder mode has no Bundles menu")
    fun openModelIsOfferedOnlyInBundleMode() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "frame construction needs a display")
        val (lib, models) = loadedLibrary()

        val bundleFrame = SingleAppFrame(track(
            SingleAppController.fromBundle("SwitchApp", lib, bundleId, models[0])
        ))
        try {
            assertEquals(
                listOf("Load JAR…", "Open Model…", "Loaded Bundles…"),
                menuItemTexts(bundleFrame.jMenuBar, "Bundles"),
                "bundle mode should offer Open Model… between Load JAR… and Loaded Bundles…"
            )
        } finally {
            bundleFrame.dispose()
        }

        val builderController = track(SingleAppController("BuilderApp", object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model = Model("BuilderModeModel", autoCSVReports = false)
        }))
        val builderFrame = SingleAppFrame(builderController)
        try {
            assertTrue(
                menuTitles(builderFrame.jMenuBar).none { it == "Bundles" },
                "builder mode has no bundle library, so it must not show a Bundles menu."
            )
        } finally {
            builderFrame.dispose()
        }
    }

    private fun menuTitles(bar: JMenuBar): List<String> =
        (0 until bar.menuCount).mapNotNull { bar.getMenu(it)?.text }

    private fun menuItemTexts(bar: JMenuBar, menuTitle: String): List<String> {
        val menu: JMenu = (0 until bar.menuCount)
            .mapNotNull { bar.getMenu(it) }
            .firstOrNull { it.text == menuTitle }
            ?: return emptyList()
        return (0 until menu.itemCount).mapNotNull { menu.getItem(it)?.text }
    }
}
