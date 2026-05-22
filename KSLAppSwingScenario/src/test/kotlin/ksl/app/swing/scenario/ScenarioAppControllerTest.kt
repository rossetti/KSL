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

package ksl.app.swing.scenario

import ksl.app.config.ExecutionMode
import ksl.app.config.ModelReference
import ksl.app.config.OutputConfig
import ksl.app.config.RunConfiguration
import ksl.app.config.RunConfigurationToml
import ksl.app.config.ScenarioSpec
import ksl.app.session.RunResult
import org.junit.jupiter.api.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 *  Phase C tests for [ScenarioAppController]: document state,
 *  scenario list mutators, save/load, dirty tracking.  No Swing
 *  components instantiated — all assertions hit the StateFlow
 *  surfaces directly.
 */
class ScenarioAppControllerTest {

    private var controller: ScenarioAppController? = null

    @AfterTest
    fun closeController() {
        controller?.close()
        controller = null
    }

    private fun fresh(appName: String = "TestApp"): ScenarioAppController =
        ScenarioAppController(appName).also { controller = it }

    private fun spec(name: String, modelName: String = "MM1") = ScenarioSpec(
        name = name,
        modelReference = ModelReference.Embedded(modelName)
    )

    // ── Initial state ──────────────────────────────────────────────────────

    @Test
    fun `fresh controller has empty scenarios, no selection, clean, no file`() {
        val c = fresh()
        assertTrue(c.scenarios.value.isEmpty())
        assertEquals(-1, c.selectedIndex.value)
        assertFalse(c.isDirty.value)
        assertNull(c.currentFile.value)
    }

    @Test
    fun `default OutputConfig has KSLDatabase enabled (Scenario default)`() {
        val c = fresh()
        // Per the locked Scenario-app design the shared SQLite DB is on
        // by default; reports default to HTML only; per-scenario CSV
        // (which lives on ScenarioSpec) stays off.
        assertTrue(c.outputConfig.value.enableKSLDatabase)
    }

    @Test
    fun `default executionMode is SEQUENTIAL`() {
        val c = fresh()
        assertEquals(ExecutionMode.SEQUENTIAL, c.executionMode.value)
    }

    @Test
    fun `submit with no scenarios is a no-op`() {
        val c = fresh()
        assertFalse(c.submit())
        assertFalse(c.runningFlow.value)
    }

    @Test
    fun `submit with all scenarios skipped is a no-op`() {
        val c = fresh()
        c.addScenario(spec("A").copy(skipOnRun = true))
        assertFalse(c.submit())
        assertFalse(c.runningFlow.value)
    }

    @Test
    fun `controller auto-discovers classpath bundles via BundleLoader`() {
        val c = fresh()
        // KSLExamples is on the test classpath; it ships MM1Bundle and
        // LKInventoryBundle as ServiceLoader-registered KSLModelBundles.
        // The exact count may grow over time — assert non-empty + provider
        // populated instead of a fixed number.
        assertTrue(c.loadedBundles.value.isNotEmpty(), "expected classpath bundles to auto-load")
        val provider = c.bundleProvider.value
        assertTrue(provider != null, "bundleProvider should be non-null when bundles are present")
        assertTrue(provider!!.modelIdentifiers().isNotEmpty(), "provider should expose at least one model")
    }

    @Test
    fun `unresolvedBundleReferences flags refs not in the loaded set`() {
        val c = fresh()
        c.addScenario(
            ScenarioSpec(
                name = "Bogus",
                modelReference = ksl.app.config.ModelReference.ByBundleAndModelId(
                    bundleId = "no.such.bundle", modelId = "no.such.model"
                )
            )
        )
        val unresolved = c.unresolvedBundleReferences()
        assertEquals(1, unresolved.size)
        assertEquals("no.such.bundle" to "no.such.model", unresolved.single())
    }

    @Test
    fun `appWorkspace nests sanitized appName under settings workspace`() {
        val c = fresh("Queueing Scenarios")
        val parent = c.settingsStore.activeWorkspace()
        // Spaces sanitize to underscores per the KSL convention.
        assertEquals(parent.resolve("Queueing_Scenarios"), c.appWorkspace)
    }

    // ── Document-level mutators ────────────────────────────────────────────

    @Test
    fun `setEnableKSLDatabase flips dirty when value changes`() {
        val c = fresh()
        assertFalse(c.isDirty.value)
        c.setEnableKSLDatabase(false)
        assertTrue(c.isDirty.value)
        assertFalse(c.outputConfig.value.enableKSLDatabase)
    }

    @Test
    fun `setEnableKSLDatabase to current value is a no-op for dirty`() {
        val c = fresh()
        c.setEnableKSLDatabase(true)  // already true
        assertFalse(c.isDirty.value)
    }

    @Test
    fun `setExecutionMode flips dirty`() {
        val c = fresh()
        c.setExecutionMode(ExecutionMode.CONCURRENT)
        assertTrue(c.isDirty.value)
        assertEquals(ExecutionMode.CONCURRENT, c.executionMode.value)
    }

    // ── Scenario list mutators ─────────────────────────────────────────────

    @Test
    fun `addScenario appends to the list and selects it`() {
        val c = fresh()
        c.addScenario(spec("baseline"))
        assertEquals(1, c.scenarios.value.size)
        assertEquals(0, c.selectedIndex.value)
        assertTrue(c.isDirty.value)
    }

    @Test
    fun `addScenario rejects duplicate names`() {
        val c = fresh()
        c.addScenario(spec("baseline"))
        assertFailsWith<IllegalArgumentException> { c.addScenario(spec("baseline")) }
    }

    @Test
    fun `cloneScenario inserts copy with unique name after source`() {
        val c = fresh()
        c.addScenario(spec("baseline"))
        c.cloneScenario(0)
        assertEquals(listOf("baseline", "baseline_copy"), c.scenarios.value.map { it.name })
        assertEquals(1, c.selectedIndex.value)  // clone selected
    }

    @Test
    fun `cloneScenario suffixes _copy_2 when _copy already exists`() {
        val c = fresh()
        c.addScenario(spec("baseline"))
        c.addScenario(spec("baseline_copy"))
        c.cloneScenario(0)
        assertEquals(
            listOf("baseline", "baseline_copy_2", "baseline_copy"),
            c.scenarios.value.map { it.name }
        )
    }

    @Test
    fun `deleteScenario shifts selection`() {
        val c = fresh()
        c.addScenario(spec("a"))
        c.addScenario(spec("b"))
        c.addScenario(spec("c"))
        c.setSelectedIndex(1)
        c.deleteScenario(1)
        assertEquals(listOf("a", "c"), c.scenarios.value.map { it.name })
        // Was index 1; remaining list has 2 entries with indices 0-1;
        // selection stays at 1 (the slot now occupied by "c").
        assertEquals(1, c.selectedIndex.value)
    }

    @Test
    fun `deleteScenario at last index shifts selection up`() {
        val c = fresh()
        c.addScenario(spec("a"))
        c.addScenario(spec("b"))
        c.setSelectedIndex(1)
        c.deleteScenario(1)
        assertEquals(0, c.selectedIndex.value)
    }

    @Test
    fun `deleteScenario emptying the list sets selection to -1`() {
        val c = fresh()
        c.addScenario(spec("a"))
        c.deleteScenario(0)
        assertEquals(-1, c.selectedIndex.value)
    }

    @Test
    fun `moveScenarioUp swaps with predecessor and selection follows`() {
        val c = fresh()
        c.addScenario(spec("a"))
        c.addScenario(spec("b"))
        c.setSelectedIndex(1)
        c.moveScenarioUp(1)
        assertEquals(listOf("b", "a"), c.scenarios.value.map { it.name })
        assertEquals(0, c.selectedIndex.value)
    }

    @Test
    fun `moveScenarioDown swaps with successor`() {
        val c = fresh()
        c.addScenario(spec("a"))
        c.addScenario(spec("b"))
        c.setSelectedIndex(0)
        c.moveScenarioDown(0)
        assertEquals(listOf("b", "a"), c.scenarios.value.map { it.name })
        assertEquals(1, c.selectedIndex.value)
    }

    @Test
    fun `moveScenarioUp at index 0 is a no-op`() {
        val c = fresh()
        c.addScenario(spec("a"))
        c.addScenario(spec("b"))
        // Reset dirty to detect new edits.
        c.markSaved(java.nio.file.Paths.get("/tmp/x.toml"))
        c.moveScenarioUp(0)
        assertEquals(listOf("a", "b"), c.scenarios.value.map { it.name })
        assertFalse(c.isDirty.value)
    }

    @Test
    fun `updateScenario rejects rename collisions`() {
        val c = fresh()
        c.addScenario(spec("a"))
        c.addScenario(spec("b"))
        assertFailsWith<IllegalArgumentException> {
            c.updateScenario(1, c.scenarios.value[1].copy(name = "a"))
        }
    }

    @Test
    fun `updateScenario rename to same name is allowed`() {
        val c = fresh()
        c.addScenario(spec("a"))
        // Rename a to itself with a different field changed.
        c.updateScenario(0, c.scenarios.value[0].copy(skipOnRun = true))
        assertTrue(c.scenarios.value[0].skipOnRun)
    }

    @Test
    fun `setSkipOnRun toggles the flag`() {
        val c = fresh()
        c.addScenario(spec("a"))
        c.setSkipOnRun(0, true)
        assertTrue(c.scenarios.value[0].skipOnRun)
    }

    @Test
    fun `setSelectedIndex does not flip dirty`() {
        val c = fresh()
        c.addScenario(spec("a"))
        c.addScenario(spec("b"))
        c.markSaved(java.nio.file.Paths.get("/tmp/x.toml"))
        c.setSelectedIndex(1)
        assertFalse(c.isDirty.value)
    }

    // ── Save / Load ────────────────────────────────────────────────────────

    @Test
    fun `currentConfiguration snapshot blanks outputDirectory`() {
        val c = fresh()
        c.addScenario(spec("a"))
        c.setOutputConfig(c.outputConfig.value.copy(outputDirectory = "/foo"))
        val cfg = c.currentConfiguration()
        assertNull(cfg.outputConfig.outputDirectory)
    }

    @Test
    fun `currentConfiguration captures scenarios, output, execution mode`() {
        val c = fresh()
        c.addScenario(spec("baseline"))
        c.setExecutionMode(ExecutionMode.CONCURRENT)
        c.setEnableKSLDatabase(false)
        val cfg = c.currentConfiguration()
        assertEquals(1, cfg.scenarios.size)
        assertEquals("baseline", cfg.scenarios.single().name)
        assertEquals(ExecutionMode.CONCURRENT, cfg.executionMode)
        assertFalse(cfg.outputConfig.enableKSLDatabase)
    }

    @Test
    fun `loadConfiguration replaces state, clears dirty, selects first scenario`() {
        val c = fresh()
        c.addScenario(spec("from-edit"))
        c.markSaved(java.nio.file.Paths.get("/tmp/old.toml"))
        c.setEnableKSLDatabase(false)
        assertTrue(c.isDirty.value)

        val loaded = RunConfiguration(
            scenarios = listOf(spec("loaded-a"), spec("loaded-b")),
            outputConfig = OutputConfig(enableKSLDatabase = true),
            executionMode = ExecutionMode.CONCURRENT
        )
        val outcome = c.loadConfiguration(loaded)
        assertTrue(outcome is ScenarioAppController.LoadResult.Loaded)
        assertEquals(listOf("loaded-a", "loaded-b"), c.scenarios.value.map { it.name })
        assertEquals(0, c.selectedIndex.value)
        assertEquals(ExecutionMode.CONCURRENT, c.executionMode.value)
        assertTrue(c.outputConfig.value.enableKSLDatabase)
        assertFalse(c.isDirty.value)
    }

    @Test
    fun `resetConfiguration empties everything`() {
        val c = fresh()
        c.addScenario(spec("a"))
        c.setExecutionMode(ExecutionMode.CONCURRENT)
        c.markSaved(java.nio.file.Paths.get("/tmp/x.toml"))
        c.setEnableKSLDatabase(false)

        c.resetConfiguration()
        assertTrue(c.scenarios.value.isEmpty())
        assertEquals(-1, c.selectedIndex.value)
        assertNull(c.currentFile.value)
        assertFalse(c.isDirty.value)
        assertEquals(ExecutionMode.SEQUENTIAL, c.executionMode.value)
        assertTrue(c.outputConfig.value.enableKSLDatabase)  // back to true default
    }

    @Test
    fun `loadConfiguration clears stale run state`() {
        val c = fresh()
        c.seedRunStateForTesting(
            lastResult = RunResult.Cancelled("prior run"),
            scenarioStatuses = mapOf("old" to ScenarioAppController.ScenarioStatus.FAILED),
            replicationProgress = mapOf("old" to (3 to 10)),
            editedSinceLastSim = true,
            running = false
        )
        val loaded = RunConfiguration(
            scenarios = listOf(spec("new")),
            outputConfig = OutputConfig(enableKSLDatabase = true),
            executionMode = ExecutionMode.SEQUENTIAL
        )
        c.loadConfiguration(loaded)
        assertNull(c.lastResult.value, "loadConfiguration must clear stale lastResult")
        assertTrue(c.scenarioStatuses.value.isEmpty(), "loadConfiguration must clear stale scenarioStatuses")
        assertTrue(c.replicationProgress.value.isEmpty(), "loadConfiguration must clear stale replicationProgress")
        assertFalse(c.editedSinceLastSim.value, "loadConfiguration must clear editedSinceLastSim")
        assertFalse(c.runningFlow.value, "loadConfiguration must clear runningFlow")
    }

    @Test
    fun `resetConfiguration clears stale run state`() {
        val c = fresh()
        c.seedRunStateForTesting(
            lastResult = RunResult.Cancelled("prior run"),
            scenarioStatuses = mapOf("old" to ScenarioAppController.ScenarioStatus.COMPLETED),
            replicationProgress = mapOf("old" to (10 to 10)),
            editedSinceLastSim = true
        )
        c.resetConfiguration()
        assertNull(c.lastResult.value)
        assertTrue(c.scenarioStatuses.value.isEmpty())
        assertTrue(c.replicationProgress.value.isEmpty())
        assertFalse(c.editedSinceLastSim.value)
    }

    @Test
    fun `clearScenarios empties the list, clears selection, marks dirty`() {
        val c = fresh()
        c.addScenario(spec("a"))
        c.addScenario(spec("b"))
        c.markSaved(java.nio.file.Paths.get("/tmp/x.toml"))
        assertFalse(c.isDirty.value)
        assertEquals(2, c.scenarios.value.size)

        c.clearScenarios()
        assertTrue(c.scenarios.value.isEmpty())
        assertEquals(-1, c.selectedIndex.value)
        assertTrue(c.isDirty.value, "clearScenarios must mark the document dirty")
    }

    @Test
    fun `clearScenarios on empty list is a no-op`() {
        val c = fresh()
        c.markSaved(java.nio.file.Paths.get("/tmp/x.toml"))
        assertFalse(c.isDirty.value)
        c.clearScenarios()
        assertFalse(c.isDirty.value, "clearScenarios on empty list must not flip dirty")
    }

    @Test
    fun `markSaved sets currentFile and clears dirty`() {
        val c = fresh()
        c.addScenario(spec("a"))
        assertTrue(c.isDirty.value)
        val path = java.nio.file.Paths.get("/tmp/x.toml")
        c.markSaved(path)
        assertEquals(path, c.currentFile.value)
        assertFalse(c.isDirty.value)
    }

    @Test
    fun `TOML round-trip preserves scenarios, output, execution mode`() {
        val c = fresh()
        c.addScenario(spec("a"))
        c.addScenario(spec("b").copy(skipOnRun = true, enableReplicationCSV = true))
        c.setExecutionMode(ExecutionMode.CONCURRENT)
        val original = c.currentConfiguration()

        val text = RunConfigurationToml.encode(original)
        val decoded = RunConfigurationToml.decode(text)

        val c2 = ScenarioAppController("RoundTripApp")
        try {
            c2.loadConfiguration(decoded)
            assertEquals(2, c2.scenarios.value.size)
            assertEquals("b", c2.scenarios.value[1].name)
            assertTrue(c2.scenarios.value[1].skipOnRun)
            assertTrue(c2.scenarios.value[1].enableReplicationCSV)
            assertEquals(ExecutionMode.CONCURRENT, c2.executionMode.value)
        } finally {
            c2.close()
        }
    }
}
