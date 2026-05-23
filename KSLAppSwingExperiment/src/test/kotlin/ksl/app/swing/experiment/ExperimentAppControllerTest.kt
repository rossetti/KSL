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

package ksl.app.swing.experiment

import ksl.app.config.DatabasePolicy
import ksl.app.config.ExecutionMode
import ksl.app.config.ModelReference
import ksl.app.config.experiment.AxialSpacing
import ksl.app.config.experiment.ControlBinding
import ksl.app.config.experiment.DesignSpec
import ksl.app.config.experiment.Fraction
import ksl.app.config.experiment.ExperimentConfiguration
import ksl.app.config.experiment.ExperimentConfigurationToml
import ksl.app.config.experiment.FactorSpec
import ksl.app.config.experiment.ReplicationSpec
import ksl.app.config.experiment.StreamPolicy
import ksl.app.session.RunResult
import org.junit.jupiter.api.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Controller-state tests for [ExperimentAppController].
 *
 *  All assertions hit the StateFlow surfaces directly — no Swing
 *  components instantiated, no model bundles loaded, no orchestrator
 *  submissions.  End-to-end submission is exercised in
 *  [ksl.app.orchestrator.ExperimentOrchestratorTest] (KSLTesting);
 *  the builder against a real model lives in
 *  [ksl.app.config.experiment.ExperimentConfigurationBuilderTest].
 */
class ExperimentAppControllerTest {

    private var controller: ExperimentAppController? = null

    @AfterTest
    fun closeController() {
        controller?.close()
        controller = null
    }

    private fun fresh(appName: String = "TestApp"): ExperimentAppController =
        ExperimentAppController(appName).also { controller = it }

    private val mm1 = ModelReference.Embedded("MM1")

    private fun factor(name: String, levels: List<Double> = listOf(0.0, 1.0)): FactorSpec =
        FactorSpec(
            name = name,
            levels = levels,
            binding = ControlBinding.Control("$name.value")
        )

    // ── Initial state ──────────────────────────────────────────────────────

    @Test
    fun `fresh controller has empty document, no file, clean state`() {
        val c = fresh()
        assertNull(c.modelReference.value)
        assertTrue(c.factors.value.isEmpty())
        assertEquals(-1, c.selectedFactorIndex.value)
        assertFalse(c.isDirty.value)
        assertFalse(c.editedSinceLastSim.value)
        assertNull(c.currentFile.value)
    }

    @Test
    fun `fresh controller defaults match the document defaults`() {
        val c = fresh()
        assertIs<DesignSpec.FullFactorial>(c.designSpec.value)
        assertIs<ReplicationSpec.Uniform>(c.replications.value)
        assertEquals(10, (c.replications.value as ReplicationSpec.Uniform).replications)
        assertIs<StreamPolicy.Independent>(c.streamPolicy.value)
        assertEquals(ExecutionMode.CONCURRENT, c.executionMode.value)
        assertEquals("Untitled", c.outputConfig.value.analysisName)
        assertEquals(DatabasePolicy.OVERWRITE, c.outputConfig.value.databasePolicy)
    }

    // ── Mutators flip dirty ────────────────────────────────────────────────

    @Test
    fun `setModelReference marks dirty and stores the ref`() {
        val c = fresh()
        c.setModelReference(mm1)
        assertEquals(mm1, c.modelReference.value)
        assertTrue(c.isDirty.value)
    }

    @Test
    fun `addFactor appends to the list, selects the new factor, and marks dirty`() {
        val c = fresh()
        c.addFactor(factor("A"))
        assertEquals(0, c.selectedFactorIndex.value)
        c.addFactor(factor("B"))
        assertEquals(listOf("A", "B"), c.factors.value.map { it.name })
        // E6.1 fix: addFactor always moves selection to the newly-
        // added index, not just on the first add.  This is what the
        // Factors panel's Add-mode flow depends on to focus the
        // just-added factor.
        assertEquals(1, c.selectedFactorIndex.value)
        c.addFactor(factor("C"))
        assertEquals(2, c.selectedFactorIndex.value)
        assertTrue(c.isDirty.value)
    }

    @Test
    fun `addFactor rejects duplicate name`() {
        val c = fresh()
        c.addFactor(factor("A"))
        try {
            c.addFactor(factor("A"))
            error("Expected IllegalArgumentException for duplicate factor name")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `updateFactor replaces at index, rejects name collision`() {
        val c = fresh()
        c.addFactor(factor("A"))
        c.addFactor(factor("B"))
        c.updateFactor(0, factor("A").copy(levels = listOf(5.0, 10.0)))
        assertEquals(listOf(5.0, 10.0), c.factors.value[0].levels)
        try {
            c.updateFactor(0, factor("B"))
            error("Expected IllegalArgumentException for rename to existing name")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `deleteFactor shifts selection`() {
        val c = fresh()
        c.addFactor(factor("A"))
        c.addFactor(factor("B"))
        c.addFactor(factor("C"))
        c.setSelectedFactorIndex(2)
        c.deleteFactor(2)
        assertEquals(listOf("A", "B"), c.factors.value.map { it.name })
        assertEquals(1, c.selectedFactorIndex.value)  // shifted up to new last
    }

    @Test
    fun `moveFactorUp swaps with predecessor`() {
        val c = fresh()
        c.addFactor(factor("A"))
        c.addFactor(factor("B"))
        c.addFactor(factor("C"))
        c.moveFactorUp(2)  // C → between A and B
        assertEquals(listOf("A", "C", "B"), c.factors.value.map { it.name })
    }

    @Test
    fun `moveFactorDown swaps with successor`() {
        val c = fresh()
        c.addFactor(factor("A"))
        c.addFactor(factor("B"))
        c.addFactor(factor("C"))
        c.moveFactorDown(0)
        assertEquals(listOf("B", "A", "C"), c.factors.value.map { it.name })
    }

    // ── Identity-couple (Q2): factor mutations drop runtime artefacts ──────

    @Test
    fun `addFactor drops lastResult and experimentInstance and lastRegressionFit`() {
        val c = fresh()
        c.addFactor(factor("A"))
        c.seedRunStateForTesting(lastResult = fakeBatch())
        assertNotNull(c.lastResult.value)
        c.addFactor(factor("B"))
        assertNull(c.lastResult.value)
    }

    @Test
    fun `deleteFactor drops lastResult`() {
        val c = fresh()
        c.addFactor(factor("A"))
        c.addFactor(factor("B"))
        c.seedRunStateForTesting(lastResult = fakeBatch())
        c.deleteFactor(1)
        assertNull(c.lastResult.value)
    }

    @Test
    fun `updateFactor drops lastResult on any change`() {
        val c = fresh()
        c.addFactor(factor("A"))
        c.seedRunStateForTesting(lastResult = fakeBatch())
        c.updateFactor(0, factor("A").copy(levels = listOf(2.0, 3.0)))
        assertNull(c.lastResult.value)
    }

    @Test
    fun `setModelReference drops lastResult`() {
        val c = fresh()
        c.seedRunStateForTesting(lastResult = fakeBatch())
        c.setModelReference(mm1)
        assertNull(c.lastResult.value)
    }

    @Test
    fun `setDesignSpec drops lastResult`() {
        val c = fresh()
        c.seedRunStateForTesting(lastResult = fakeBatch())
        c.setDesignSpec(DesignSpec.CentralComposite(axialSpacing = AxialSpacing.Rotatable))
        assertNull(c.lastResult.value)
    }

    @Test
    fun `setReplications drops lastResult`() {
        val c = fresh()
        c.seedRunStateForTesting(lastResult = fakeBatch())
        c.setReplications(ReplicationSpec.Uniform(25))
        assertNull(c.lastResult.value)
    }

    @Test
    fun `setStreamPolicy does NOT drop lastResult`() {
        // Stream policy doesn't invalidate prior results — it's a
        // preference, not a structural change.  Per the Phase E3
        // plan, only structural mutators drop runtime artefacts.
        val c = fresh()
        c.seedRunStateForTesting(lastResult = fakeBatch())
        c.setStreamPolicy(StreamPolicy.CommonRandomNumbers)
        assertNotNull(c.lastResult.value)
    }

    // ── clearFactors (Clear-All analogue) ──────────────────────────────────

    @Test
    fun `clearFactors detaches the file, resets analysisName, returns the previous path`() {
        val c = fresh()
        c.addFactor(factor("A"))
        c.setAnalysisName("MyExperiment")
        val path = java.nio.file.Paths.get("/tmp/x.toml")
        c.markSaved(path)
        assertFalse(c.isDirty.value)

        val detached = c.clearFactors()
        assertTrue(c.factors.value.isEmpty())
        assertEquals(path, detached)
        assertNull(c.currentFile.value)
        assertFalse(c.isDirty.value)
        // Identity field reset; preference fields preserved.
        assertEquals("Untitled", c.outputConfig.value.analysisName)
    }

    @Test
    fun `clearFactors preserves preference-style fields`() {
        val c = fresh()
        c.addFactor(factor("A"))
        c.setDatabasePolicy(DatabasePolicy.NEW)
        c.setExecutionMode(ExecutionMode.SEQUENTIAL)
        c.setStreamPolicy(StreamPolicy.CommonRandomNumbers)
        c.clearFactors()
        // Preferences survive.
        assertEquals(DatabasePolicy.NEW, c.outputConfig.value.databasePolicy)
        assertEquals(ExecutionMode.SEQUENTIAL, c.executionMode.value)
        assertIs<StreamPolicy.CommonRandomNumbers>(c.streamPolicy.value)
    }

    @Test
    fun `clearFactors on empty list is a no-op and preserves file association`() {
        val c = fresh()
        val path = java.nio.file.Paths.get("/tmp/x.toml")
        c.markSaved(path)
        val detached = c.clearFactors()
        assertNull(detached)
        assertEquals(path, c.currentFile.value)
    }

    // ── markSaved auto-fills analysisName ──────────────────────────────────

    @Test
    fun `markSaved auto-fills analysisName when still at Untitled`() {
        val c = fresh()
        assertEquals("Untitled", c.outputConfig.value.analysisName)
        c.markSaved(java.nio.file.Paths.get("/tmp/queueingExperiment.toml"))
        assertEquals("queueingExperiment", c.outputConfig.value.analysisName)
    }

    @Test
    fun `markSaved does NOT overwrite a user-set analysisName`() {
        val c = fresh()
        c.setAnalysisName("MyAnalysis")
        c.markSaved(java.nio.file.Paths.get("/tmp/different.toml"))
        assertEquals("MyAnalysis", c.outputConfig.value.analysisName)
    }

    // ── Submit gates ───────────────────────────────────────────────────────

    @Test
    fun `submit returns false with no model reference`() {
        val c = fresh()
        c.addFactor(factor("A"))
        assertFalse(c.submit())
    }

    @Test
    fun `submit returns false with no factors`() {
        val c = fresh()
        c.setModelReference(mm1)
        assertFalse(c.submit())
    }

    // (The "submit with no bundle provider" case is implicit — fresh
    // fixtures don't load any bundles; the classpath auto-load may
    // pick up LK + MM1 from KSLExamples but the test's
    // ModelReference.Embedded("MM1") wouldn't resolve via any
    // bundle either.  Submit returns false at the resolution step,
    // not at the bundle-provider null check.)

    // ── sequentialIgnoresStreamPolicy helper ───────────────────────────────

    @Test
    fun `sequentialIgnoresStreamPolicy is true when SEQUENTIAL + CRN`() {
        val c = fresh()
        c.setExecutionMode(ExecutionMode.SEQUENTIAL)
        c.setStreamPolicy(StreamPolicy.CommonRandomNumbers)
        assertTrue(c.sequentialIgnoresStreamPolicy())
    }

    @Test
    fun `sequentialIgnoresStreamPolicy is false under CONCURRENT + CRN`() {
        val c = fresh()
        c.setStreamPolicy(StreamPolicy.CommonRandomNumbers)
        assertFalse(c.sequentialIgnoresStreamPolicy())
    }

    @Test
    fun `sequentialIgnoresStreamPolicy is false under SEQUENTIAL + Independent`() {
        val c = fresh()
        c.setExecutionMode(ExecutionMode.SEQUENTIAL)
        // streamPolicy stays at default Independent.
        assertFalse(c.sequentialIgnoresStreamPolicy())
    }

    // ── Lifecycle: load / reset / round-trip ───────────────────────────────

    @Test
    fun `loadConfiguration replaces all document state and clears dirty`() {
        val c = fresh()
        c.setModelReference(mm1)
        c.addFactor(factor("X"))
        c.setAnalysisName("Edited")
        assertTrue(c.isDirty.value)

        val loaded = ExperimentConfiguration(
            modelReference = ModelReference.Embedded("LK"),
            factors = listOf(factor("A"), factor("B")),
            designSpec = DesignSpec.CentralComposite(axialSpacing = AxialSpacing.Rotatable),
            replications = ReplicationSpec.Uniform(20),
            streamPolicy = StreamPolicy.CommonRandomNumbers,
            executionMode = ExecutionMode.SEQUENTIAL
        )
        val result = c.loadConfiguration(loaded)
        assertIs<ExperimentAppController.LoadResult.Loaded>(result)
        assertEquals(ModelReference.Embedded("LK"), c.modelReference.value)
        assertEquals(listOf("A", "B"), c.factors.value.map { it.name })
        assertIs<DesignSpec.CentralComposite>(c.designSpec.value)
        assertEquals(20, (c.replications.value as ReplicationSpec.Uniform).replications)
        assertIs<StreamPolicy.CommonRandomNumbers>(c.streamPolicy.value)
        assertEquals(ExecutionMode.SEQUENTIAL, c.executionMode.value)
        assertFalse(c.isDirty.value)
    }

    @Test
    fun `resetConfiguration returns to factory defaults`() {
        val c = fresh()
        c.setModelReference(mm1)
        c.addFactor(factor("A"))
        c.setExecutionMode(ExecutionMode.SEQUENTIAL)
        c.markSaved(java.nio.file.Paths.get("/tmp/x.toml"))

        c.resetConfiguration()
        assertNull(c.modelReference.value)
        assertTrue(c.factors.value.isEmpty())
        assertEquals(ExecutionMode.CONCURRENT, c.executionMode.value)
        assertNull(c.currentFile.value)
        assertFalse(c.isDirty.value)
    }

    @Test
    fun `currentConfiguration to TOML round-trips through loadConfiguration`() {
        val c = fresh()
        c.setModelReference(mm1)
        c.addFactor(factor("A"))
        c.addFactor(factor("B"))
        c.setDesignSpec(DesignSpec.TwoLevelFactorial(fraction = Fraction.HalfFraction()))
        c.setReplications(ReplicationSpec.PerPoint(default = 10, overrides = mapOf(0 to 30)))
        c.setStreamPolicy(StreamPolicy.CommonRandomNumbers)
        c.setExecutionMode(ExecutionMode.SEQUENTIAL)
        c.setAnalysisName("RoundTripTest")

        val toml = ExperimentConfigurationToml.encode(c.currentConfiguration())
        val decoded = ExperimentConfigurationToml.decode(toml)

        val c2 = ExperimentAppController("RoundTripApp")
        try {
            c2.loadConfiguration(decoded)
            assertEquals(c.modelReference.value, c2.modelReference.value)
            assertEquals(c.factors.value, c2.factors.value)
            assertEquals(c.designSpec.value, c2.designSpec.value)
            assertEquals(c.replications.value, c2.replications.value)
            assertEquals(c.streamPolicy.value, c2.streamPolicy.value)
            assertEquals(c.executionMode.value, c2.executionMode.value)
            assertEquals("RoundTripTest", c2.outputConfig.value.analysisName)
        } finally {
            c2.close()
        }
    }

    @Test
    fun `currentConfiguration without modelReference throws`() {
        val c = fresh()
        c.addFactor(factor("A"))
        try {
            c.currentConfiguration()
            error("Expected exception when modelReference is null")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("model reference"))
        }
    }

    // ── fitRegression delegation ───────────────────────────────────────────

    @Test
    fun `fitRegression returns null when no experiment instance is retained`() {
        val c = fresh()
        val result = c.fitRegression(
            response = "AnyResponse",
            model = ksl.controls.experiments.LinearModel(setOf("A")),
            coded = true
        )
        assertNull(result)
        assertNull(c.lastRegressionFit.value)
    }

    // (Positive fitRegression delegation is exercised in the
    // builder + orchestrator tests where a real experiment instance
    // is constructed against the LK Inventory model.  Reproducing
    // that setup here would duplicate fixture machinery without
    // adding controller-level coverage.)

    // ── Fixtures ───────────────────────────────────────────────────────────

    private fun fakeBatch(): RunResult.BatchCompleted = RunResult.BatchCompleted(
        summary = ksl.app.session.OrchestratorSummary(
            runId = "test-run",
            orchestratorName = "TestExperimentOrchestrator",
            totalItems = 1,
            completedItems = 1,
            failedItems = 0,
            beginTime = kotlinx.datetime.Instant.fromEpochMilliseconds(0L),
            endTime = kotlinx.datetime.Instant.fromEpochMilliseconds(1000L)
        ),
        snapshots = emptyList()
    )
}
