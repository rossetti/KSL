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
import ksl.app.config.OutputConfig
import ksl.app.config.ReportFormat
import ksl.app.config.RunConfiguration
import ksl.app.config.RunConfigurationToml
import ksl.app.config.ScenarioSpec
import ksl.app.editor.BundleLibraryController
import ksl.examples.general.appsupport.MM1Bundle
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import org.junit.jupiter.api.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
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

    // ── Output Options ─────────────────────────────────────────────────────

    @Test
    fun `fresh controller has default OutputConfig`() {
        val c = freshController()
        val cfg = c.outputConfig.value
        assertFalse(cfg.enableKSLDatabase)
        assertFalse(cfg.enableReplicationCSV)
        assertFalse(cfg.enableExperimentCSV)
        assertEquals(setOf(ReportFormat.HTML), cfg.reports)
    }

    @Test
    fun `output-options mutators flip dirty and editedSinceLastSim`() {
        val c = freshController()
        c.setEnableKSLDatabase(true)
        assertTrue(c.isDirty.value)
        assertTrue(c.editedSinceLastSim.value)
        assertTrue(c.outputConfig.value.enableKSLDatabase)
    }

    @Test
    fun `setReportFormatEnabled toggles individual formats`() {
        val c = freshController()
        c.setReportFormatEnabled(ReportFormat.MARKDOWN, true)
        assertEquals(setOf(ReportFormat.HTML, ReportFormat.MARKDOWN), c.outputConfig.value.reports)
        c.setReportFormatEnabled(ReportFormat.HTML, false)
        assertEquals(setOf(ReportFormat.MARKDOWN), c.outputConfig.value.reports)
        // Idempotent — removing absent is a no-op.
        val before = c.isDirty.value
        c.setReportFormatEnabled(ReportFormat.HTML, false)
        // (already absent — mutator should early-return without flipping dirty,
        // but since we just set dirty=true above this test only confirms no error)
        assertTrue(c.isDirty.value || before)
    }

    @Test
    fun `currentConfiguration round-trips outputConfig with outputDirectory blanked`() {
        val c = freshController()
        c.setEnableKSLDatabase(true)
        c.setEnableReplicationCSV(true)
        val cfg = c.currentConfiguration()
        assertTrue(cfg.outputConfig.enableKSLDatabase)
        assertTrue(cfg.outputConfig.enableReplicationCSV)
        assertFalse(cfg.outputConfig.enableExperimentCSV)
        // outputDirectory is install-local — should be null in the
        // snapshot so saved TOML doesn't bake in an absolute path.
        assertNull(cfg.outputConfig.outputDirectory)
    }

    @Test
    fun `loadConfiguration restores outputConfig`() {
        val c = freshController()
        val loaded = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "ConfigTestApp",
                    modelReference = ModelReference.Embedded("ConfigTestApp")
                )
            ),
            outputConfig = OutputConfig(
                enableKSLDatabase = true,
                enableExperimentCSV = true,
                reports = setOf(ReportFormat.HTML, ReportFormat.MARKDOWN)
            )
        )
        val outcome = c.loadConfiguration(loaded)
        assertTrue(outcome is SingleAppController.LoadResult.Loaded)
        assertTrue(c.outputConfig.value.enableKSLDatabase)
        assertFalse(c.outputConfig.value.enableReplicationCSV)
        assertTrue(c.outputConfig.value.enableExperimentCSV)
        assertEquals(setOf(ReportFormat.HTML, ReportFormat.MARKDOWN), c.outputConfig.value.reports)
        // outputDirectory should be cleared by loadConfiguration so the
        // install-local path is re-applied at submit time.
        assertNull(c.outputConfig.value.outputDirectory)
    }

    @Test
    fun `resetConfiguration restores default OutputConfig with analysisName pre-filled from modelName`() {
        // E.5.x analysisName-pre-fill: reset mirrors the init-time
        // behavior — analysisName takes the probe-captured
        // modelName, other fields restore to the OutputConfig
        // data-class defaults.
        val c = freshController()
        c.setEnableKSLDatabase(true)
        c.setReportFormatEnabled(ReportFormat.MARKDOWN, true)
        c.resetConfiguration()
        assertEquals(OutputConfig(analysisName = "ConfigTestModel"), c.outputConfig.value)
    }

    @Test
    fun `modelName from the probe is the sanitized model name`() {
        // ConfigTestModel has no spaces; modelName == simulationName as a string.
        val c = freshController()
        assertEquals("ConfigTestModel", c.modelName)
    }

    // ── analysisName pre-fill from modelName (E.5.x UX fix) ─────────────

    @Test
    fun `fresh builder mode controller pre-fills analysisName with modelName`() {
        // The Output Name field should show the model name from
        // the first frame the user sees — not the bare "Untitled"
        // sentinel — so the visible value matches the underlying
        // output directory + DB stem fallback.
        val c = freshController()
        assertEquals("ConfigTestModel", c.outputConfig.value.analysisName)
    }

    @Test
    fun `fresh bundle mode controller pre-fills analysisName with modelName`() {
        // Same pre-fill applies regardless of launch mode — the
        // bundle picker's chosen model is just as known at probe
        // time as a developer-supplied builder's model.
        val c = freshBundleModeController()
        assertEquals(c.modelName, c.outputConfig.value.analysisName)
    }

    @Test
    fun `loadConfiguration preserves analysisName from the loaded config`() {
        // Pre-fill only governs the launch / reset path — a load
        // takes whatever the TOML carried.  The user's stored name
        // wins over the model-name default.
        val c = freshController()
        assertEquals("ConfigTestModel", c.outputConfig.value.analysisName)
        val loaded = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "ConfigTestApp",
                    modelReference = ModelReference.Embedded("ConfigTestApp")
                )
            ),
            outputConfig = OutputConfig(analysisName = "MyCustomAnalysis")
        )
        c.loadConfiguration(loaded)
        assertEquals("MyCustomAnalysis", c.outputConfig.value.analysisName,
            "Loaded analysisName must survive the load — pre-fill must not override.")
    }

    @Test
    fun `probe-failure controller keeps Untitled sentinel`() {
        // When the probe throws, modelName is blank — the pre-fill
        // skips so the "Untitled" sentinel continues to drive the
        // downstream fallback paths.
        val throwingBuilder = object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model = error("Synthetic probe failure")
        }
        val c = SingleAppController("ProbeFailApp", throwingBuilder)
        controller = c
        assertEquals("", c.modelName,
            "Probe failure must leave modelName blank.")
        assertEquals("Untitled", c.outputConfig.value.analysisName,
            "Probe failure must leave the analysisName sentinel intact.")
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

    // ── Bundle-mode launch (E.5.x — bundle fallback feature) ────────────

    private val mm1BundleId = MM1Bundle().bundleId
    private val mm1ModelId = MM1Bundle.MODEL_ID

    /** Construct a bundle-mode controller backed by a real
     *  [BundleLibraryController] with the classpath bundles
     *  auto-discovered.  [appName] follows the same default as
     *  [freshController]. */
    private fun freshBundleModeController(
        appName: String = "ConfigTestApp"
    ): SingleAppController {
        val lib = BundleLibraryController()
        lib.discoverFromClasspath()
        val c = SingleAppController(
            appName = appName,
            modelBuilder = builder,    // any builder suffices for probe
            bundleLibrary = lib,
            sourceRef = ModelReference.ByBundleAndModelId(mm1BundleId, mm1ModelId)
        )
        controller = c
        return c
    }

    @Test
    fun `builder mode rejects a ByBundleAndModelId config with WrongMode`() {
        val c = freshController()
        val bundleConfig = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "ConfigTestApp",
                    modelReference = ModelReference.ByBundleAndModelId(
                        mm1BundleId, mm1ModelId
                    )
                )
            )
        )
        val outcome = c.loadConfiguration(bundleConfig)
        assertTrue(
            outcome is SingleAppController.LoadResult.WrongMode,
            "Builder-mode controller must reject ByBundleAndModelId with WrongMode."
        )
        val msg = (outcome as SingleAppController.LoadResult.WrongMode).reason
        assertTrue("modelBuilder" in msg && "bundle" in msg.lowercase(),
            "WrongMode reason should mention modelBuilder and bundle; was: $msg")
    }

    @Test
    fun `builder mode loadConfiguration leaves controller state unchanged on WrongMode`() {
        val c = freshController()
        c.updateRunOverride { it.copy(numberOfReplications = 99) }
        assertTrue(c.isDirty.value)
        val before = c.runOverrides.value
        val bundleConfig = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "ConfigTestApp",
                    modelReference = ModelReference.ByBundleAndModelId(
                        mm1BundleId, mm1ModelId
                    )
                )
            )
        )
        c.loadConfiguration(bundleConfig)
        assertEquals(before, c.runOverrides.value,
            "WrongMode must not mutate the controller's overrides.")
        assertTrue(c.isDirty.value,
            "WrongMode must not clear the existing dirty flag.")
    }

    @Test
    fun `bundle mode currentConfiguration writes ByBundleAndModelId`() {
        val c = freshBundleModeController()
        val cfg = c.currentConfiguration()
        val ref = cfg.scenarios.single().modelReference
        assertTrue(ref is ModelReference.ByBundleAndModelId,
            "Bundle-mode controller must write ByBundleAndModelId; was: $ref")
        ref as ModelReference.ByBundleAndModelId
        assertEquals(mm1BundleId, ref.bundleId)
        assertEquals(mm1ModelId, ref.modelId)
    }

    @Test
    fun `bundle mode loads a matching ByBundleAndModelId config without warning`() {
        val c = freshBundleModeController()
        val cfg = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "ConfigTestApp",
                    modelReference = ModelReference.ByBundleAndModelId(
                        mm1BundleId, mm1ModelId
                    ),
                    runOverrides = ExperimentRunOverrides(numberOfReplications = 5)
                )
            )
        )
        val outcome = c.loadConfiguration(cfg)
        assertTrue(outcome is SingleAppController.LoadResult.Loaded,
            "Matching ByBundleAndModelId in bundle mode must load.")
        assertNull((outcome as SingleAppController.LoadResult.Loaded).warning,
            "Matching ByBundleAndModelId must not produce a warning.")
        assertEquals(5, c.runOverrides.value.numberOfReplications)
    }

    @Test
    fun `bundle mode rejects an Embedded config with WrongMode`() {
        val c = freshBundleModeController()
        val embeddedConfig = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "ConfigTestApp",
                    modelReference = ModelReference.Embedded("SomeOtherApp")
                )
            )
        )
        val outcome = c.loadConfiguration(embeddedConfig)
        assertTrue(outcome is SingleAppController.LoadResult.WrongMode,
            "Bundle-mode controller must reject Embedded with WrongMode.")
        val msg = (outcome as SingleAppController.LoadResult.WrongMode).reason
        assertTrue("developer-supplied" in msg || "modelBuilder" in msg,
            "WrongMode reason should mention developer-supplied / modelBuilder; was: $msg")
    }

    @Test
    fun `bundle mode rejects a ByBundleAndModelId config whose bundle is not loaded`() {
        val c = freshBundleModeController()
        val unknownConfig = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "ConfigTestApp",
                    modelReference = ModelReference.ByBundleAndModelId(
                        bundleId = "not-a-real-bundle-id-xyz",
                        modelId = "any"
                    )
                )
            )
        )
        val outcome = c.loadConfiguration(unknownConfig)
        assertTrue(outcome is SingleAppController.LoadResult.Rejected,
            "Bundle-not-loaded must yield Rejected.")
        val msg = (outcome as SingleAppController.LoadResult.Rejected).reason
        assertTrue("not-a-real-bundle-id-xyz" in msg && "Load JAR" in msg,
            "Rejected reason should name the missing bundle and point to Load JAR; was: $msg")
    }

    @Test
    fun `bundle mode loads a different-modelId ByBundleAndModelId with warning`() {
        // Use the same bundleId but a fake modelId.  The bundle IS
        // loaded (so the bundle-not-loaded rejection does NOT fire),
        // but the (bundleId, modelId) pair does not match the
        // session's sourceRef → load proceeds with a warning.
        val c = freshBundleModeController()
        val cfg = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "ConfigTestApp",
                    modelReference = ModelReference.ByBundleAndModelId(
                        bundleId = mm1BundleId,
                        modelId = "some-other-model-id"
                    ),
                    runOverrides = ExperimentRunOverrides(numberOfReplications = 4)
                )
            )
        )
        val outcome = c.loadConfiguration(cfg)
        assertTrue(outcome is SingleAppController.LoadResult.Loaded,
            "Mismatched modelId on a loaded bundle must Load (with warning).")
        val warning = (outcome as SingleAppController.LoadResult.Loaded).warning
        assertNotNull(warning, "Mismatched modelId must produce a warning.")
        assertTrue("some-other-model-id" in warning,
            "Warning should name the loaded modelId; was: $warning")
        assertEquals(4, c.runOverrides.value.numberOfReplications,
            "Overrides should still apply.")
    }

    @Test
    fun `bundle mode controller exposes its bundle library`() {
        val c = freshBundleModeController()
        assertNotNull(c.bundleLibrary,
            "Bundle-mode controller must expose a non-null bundleLibrary.")
        assertTrue(c.bundleLibrary!!.findBundle(mm1BundleId) != null,
            "The exposed library should contain the auto-discovered MM1 bundle.")
    }

    @Test
    fun `builder mode controller has a null bundle library`() {
        val c = freshController()
        assertNull(c.bundleLibrary,
            "Builder-mode controller must expose a null bundleLibrary.")
    }

    @Test
    fun `bundle mode sourceRef defaults to ByBundleAndModelId when explicitly passed`() {
        // The default sourceRef value on the controller is
        // ModelReference.Embedded(appName); bundle mode must pass
        // a ByBundleAndModelId explicitly.  Verify that what's
        // passed is what's surfaced.
        val c = freshBundleModeController()
        assertEquals(
            ModelReference.ByBundleAndModelId(mm1BundleId, mm1ModelId),
            c.sourceRef
        )
    }

    @Test
    fun `builder mode sourceRef defaults to Embedded keyed on appName`() {
        val c = freshController("CustomName")
        assertEquals(ModelReference.Embedded("CustomName"), c.sourceRef)
    }
}
