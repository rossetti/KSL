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

import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.RunConfiguration
import ksl.app.config.RunConfigurationToml
import ksl.app.config.ScenarioSpec
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import org.junit.jupiter.api.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * Tests for [SingleAppController]'s configuration snapshot / load /
 * dirty-tracking surface (the save/load feature).
 *
 * No model fixture is needed to exercise the run-overrides flow — the
 * controller's probe call accepts a trivial builder whose model has
 * zero controls and zero RVs.  When more elaborate fixtures are
 * required (e.g. testing control-override snapshots) the test
 * constructs one inline.
 */
class SingleAppControllerConfigurationTest {

    private val builder = object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model = Model("ConfigTestModel", autoCSVReports = false)
    }

    private var controller: SingleAppController? = null

    @AfterTest
    fun closeController() {
        controller?.close()
        controller = null
    }

    private fun freshController(appName: String = "ConfigTestApp"): SingleAppController {
        val c = SingleAppController(appName, builder)
        controller = c
        return c
    }

    @Test
    fun `fresh controller starts clean with no file association`() {
        val c = freshController()
        assertFalse(c.isDirty.value, "fresh controller should not be dirty")
        assertNull(c.currentFile.value, "fresh controller should have no current file")
    }

    @Test
    fun `editing a run override flips dirty true`() {
        val c = freshController()
        c.updateRunOverride { it.copy(numberOfReplications = 42) }
        assertTrue(c.isDirty.value, "edit should flip dirty true")
    }

    @Test
    fun `no-op transform leaves dirty false`() {
        val c = freshController()
        c.updateRunOverride { it }  // identity transform — no change
        assertFalse(c.isDirty.value, "no-op transform should not flip dirty")
    }

    @Test
    fun `currentConfiguration reflects all three override flows`() {
        val c = freshController("MyApp")
        c.updateRunOverride { it.copy(numberOfReplications = 50, lengthOfReplication = 250.0) }
        val cfg = c.currentConfiguration()
        assertEquals(1, cfg.scenarios.size)
        val spec = cfg.scenarios.single()
        assertEquals("MyApp", spec.name)
        assertTrue(spec.modelReference is ModelReference.Embedded)
        assertEquals(50, spec.runOverrides?.numberOfReplications)
        assertEquals(250.0, spec.runOverrides?.lengthOfReplication)
        assertEquals(0, spec.controlOverrides.totalControls)
        assertEquals(0, spec.rvOverrides.size)
    }

    @Test
    fun `loadConfiguration applies the scenario and clears dirty`() {
        val c = freshController()
        // Pre-dirty the controller with a different value.
        c.updateRunOverride { it.copy(numberOfReplications = 99) }
        assertTrue(c.isDirty.value)
        // Load a configuration carrying numberOfReplications = 17.
        val loaded = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "ConfigTestApp",
                    modelReference = ModelReference.Embedded("ConfigTestApp"),
                    runOverrides = ExperimentRunOverrides(numberOfReplications = 17)
                )
            )
        )
        val outcome = c.loadConfiguration(loaded)
        assertTrue(outcome is SingleAppController.LoadResult.Loaded)
        assertEquals(17, c.runOverrides.value.numberOfReplications)
        assertFalse(c.isDirty.value, "load should clear dirty")
        assertNull((outcome as SingleAppController.LoadResult.Loaded).warning)
    }

    @Test
    fun `loadConfiguration warns when modelReference name mismatches appName`() {
        val c = freshController("AppA")
        val foreign = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "AppA",
                    modelReference = ModelReference.Embedded("AppB")
                )
            )
        )
        val outcome = c.loadConfiguration(foreign)
        assertTrue(outcome is SingleAppController.LoadResult.Loaded)
        val warning = (outcome as SingleAppController.LoadResult.Loaded).warning
        assertTrue(warning != null && "AppB" in warning && "AppA" in warning)
    }

    @Test
    fun `loadConfiguration rejects an empty configuration`() {
        val c = freshController()
        val outcome = c.loadConfiguration(RunConfiguration(scenarios = emptyList()))
        assertTrue(outcome is SingleAppController.LoadResult.Rejected)
    }

    @Test
    fun `markSaved associates a file and clears dirty`() {
        val c = freshController()
        c.updateRunOverride { it.copy(numberOfReplications = 7) }
        assertTrue(c.isDirty.value)
        val path = java.nio.file.Paths.get("/tmp/fake/path.toml")
        c.markSaved(path)
        assertEquals(path, c.currentFile.value)
        assertFalse(c.isDirty.value)
    }

    @Test
    fun `resetConfiguration clears overrides and file association`() {
        val c = freshController()
        c.updateRunOverride { it.copy(numberOfReplications = 11) }
        c.markSaved(java.nio.file.Paths.get("/tmp/x.toml"))
        c.updateRunOverride { it.copy(numberOfReplications = 12) }  // dirty again
        c.resetConfiguration()
        assertEquals(ExperimentRunOverrides(), c.runOverrides.value)
        assertNull(c.currentFile.value)
        assertFalse(c.isDirty.value)
    }

    @Test
    fun `currentConfiguration always emits a non-null runOverrides`() {
        val c = freshController()
        // No edits — value is the all-null default ExperimentRunOverrides.
        val cfg = c.currentConfiguration()
        assertEquals(ExperimentRunOverrides(), cfg.scenarios.single().runOverrides,
            "snapshot should carry a non-null empty ExperimentRunOverrides so encoded TOML " +
                "shows an empty [scenarios.runOverrides] section header")
    }

    @Test
    fun `currentConfiguration retains runOverrides once any field is set`() {
        val c = freshController()
        c.updateRunOverride { it.copy(numberOfReplications = 5) }
        val cfg = c.currentConfiguration()
        assertEquals(5, cfg.scenarios.single().runOverrides?.numberOfReplications)
    }

    @Test
    fun `encoded TOML emits an empty runOverrides section for an unedited configuration`() {
        val c = freshController()
        val text = RunConfigurationToml.encode(c.currentConfiguration())
        assertTrue(
            text.contains("[scenarios.runOverrides]"),
            "encoded TOML should keep the runOverrides section header (self-documenting); got:\n$text"
        )
        // No `field = null` lines should appear under the header.
        for (field in listOf(
            "numberOfReplications", "numChunks", "startingRepId", "lengthOfReplication",
            "lengthOfReplicationWarmUp", "replicationInitializationOption",
            "maximumAllowedExecutionTimePerReplication", "resetStartStreamOption",
            "advanceNextSubStreamOption", "antitheticOption",
            "numberOfStreamAdvancesPriorToRunning", "garbageCollectAfterReplicationFlag"
        )) {
            assertFalse(text.contains("$field = null"), "unexpected null line for $field in:\n$text")
        }
    }

    @Test
    fun `encoded TOML emits only set fields when runOverrides is partially edited`() {
        val c = freshController()
        c.updateRunOverride { it.copy(numberOfReplications = 7) }
        val text = RunConfigurationToml.encode(c.currentConfiguration())
        assertTrue(text.contains("[scenarios.runOverrides]"))
        assertTrue(text.contains("numberOfReplications = 7"))
        // The 11 unset fields should NOT appear as `field = null` lines.
        for (field in listOf(
            "numChunks", "startingRepId", "lengthOfReplication",
            "lengthOfReplicationWarmUp", "replicationInitializationOption",
            "maximumAllowedExecutionTimePerReplication", "resetStartStreamOption",
            "advanceNextSubStreamOption", "antitheticOption",
            "numberOfStreamAdvancesPriorToRunning", "garbageCollectAfterReplicationFlag"
        )) {
            assertFalse(text.contains("$field = null"), "unexpected null line for $field in:\n$text")
        }
    }

    @Test
    fun `fresh controller has editedSinceLastSim = false`() {
        val c = freshController()
        assertFalse(c.editedSinceLastSim.value)
    }

    @Test
    fun `editing flips editedSinceLastSim true`() {
        val c = freshController()
        c.updateRunOverride { it.copy(numberOfReplications = 50) }
        assertTrue(c.editedSinceLastSim.value)
    }

    @Test
    fun `markSaved does NOT clear editedSinceLastSim`() {
        val c = freshController()
        c.updateRunOverride { it.copy(numberOfReplications = 50) }
        assertTrue(c.editedSinceLastSim.value)
        c.markSaved(java.nio.file.Paths.get("/tmp/x.toml"))
        // Saving clears isDirty (the editor now matches the file) but
        // leaves editedSinceLastSim alone — saving has nothing to do
        // with simulating.  The status badge therefore stays "Edited /
        // Previous run: …" after Save, which was the user's bug.
        assertFalse(c.isDirty.value)
        assertTrue(c.editedSinceLastSim.value, "save should not clear editedSinceLastSim")
    }

    @Test
    fun `resetConfiguration clears lastResult and editedSinceLastSim`() {
        val c = freshController()
        c.updateRunOverride { it.copy(numberOfReplications = 50) }
        assertTrue(c.editedSinceLastSim.value)
        c.resetConfiguration()
        assertFalse(c.editedSinceLastSim.value, "reset should clear editedSinceLastSim")
        assertNull(c.lastResult.value, "reset should clear lastResult so Reports tab disables")
    }

    @Test
    fun `loadConfiguration clears lastResult and editedSinceLastSim`() {
        val c = freshController()
        val loaded = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "ConfigTestApp",
                    modelReference = ModelReference.Embedded("ConfigTestApp"),
                    runOverrides = ExperimentRunOverrides(numberOfReplications = 11)
                )
            )
        )
        val outcome = c.loadConfiguration(loaded)
        assertTrue(outcome is SingleAppController.LoadResult.Loaded)
        assertFalse(c.editedSinceLastSim.value, "load should clear editedSinceLastSim")
        assertNull(c.lastResult.value, "load should clear lastResult")
    }

    @Test
    fun `modelName from the probe is the sanitized model name`() {
        // ConfigTestModel has no spaces; modelName == simulationName as a string.
        val c = freshController()
        assertEquals("ConfigTestModel", c.modelName)
    }

    @Test
    fun `appWorkspace nests under settingsStore activeWorkspace by modelName`() {
        val c = freshController()
        val parent = c.settingsStore.activeWorkspace()
        assertEquals(parent.resolve("ConfigTestModel"), c.appWorkspace)
    }

    @Test
    fun `model with spaces in simulationName produces a workspace-safe modelName`() {
        // Verify the existing Model sanitization (spaces → underscores) lands
        // a clean directory segment without us reinventing a sanitizer.
        val spacedBuilder = object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model = Model("My Sim Name", autoCSVReports = false)
        }
        val c = SingleAppController("Spaced App", spacedBuilder)
        try {
            assertEquals("My_Sim_Name", c.modelName)
            val parent = c.settingsStore.activeWorkspace()
            assertEquals(parent.resolve("My_Sim_Name"), c.appWorkspace)
        } finally {
            c.close()
        }
    }

    @Test
    fun `TOML round-trip via RunConfigurationToml preserves overrides`() {
        val c = freshController("RoundTripApp")
        c.updateRunOverride { it.copy(numberOfReplications = 13, lengthOfReplication = 333.0) }
        val original = c.currentConfiguration()

        val text = RunConfigurationToml.encode(original)
        val decoded = RunConfigurationToml.decode(text)

        val c2 = SingleAppController("RoundTripApp", builder)
        try {
            val outcome = c2.loadConfiguration(decoded)
            assertTrue(outcome is SingleAppController.LoadResult.Loaded)
            assertEquals(13, c2.runOverrides.value.numberOfReplications)
            assertEquals(333.0, c2.runOverrides.value.lengthOfReplication)
        } finally {
            c2.close()
        }
    }
}
