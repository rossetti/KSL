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

package ksl.app.swing.simopt

import ksl.app.config.ModelReference
import ksl.app.config.optimization.EvaluationSpec
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.app.config.optimization.SolverSpec
import ksl.app.config.optimization.SolverTrackingSpec
import ksl.app.swing.simopt.runsetup.RunSetupPaths
import ksl.examples.general.appsupport.MM1Bundle
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Phase O7a controller-level tests.
 *
 *  Pins the preference semantics of `setEvaluationSpec` and
 *  `setTrackingSpec` (mark dirty but do NOT drop `lastResult`),
 *  the path-resolution helpers in `RunSetupPaths`, and TOML
 *  round-trip of both spec types.
 */
class SimoptAppRunSetupTest {

    private val mm1BundleId = MM1Bundle().bundleId
    private val mm1ModelId = MM1Bundle.MODEL_ID
    private fun mm1Ref() = ModelReference.ByBundleAndModelId(mm1BundleId, mm1ModelId)

    private fun seedRunnableProblem(c: SimoptAppController) {
        c.setModelReference(mm1Ref())
        val descriptor = c.currentModelDescriptor.value
        assertNotNull(descriptor)
        c.setObjectiveResponseName(descriptor.responseNames.first())
        c.addInput(OptimizationInputSpec("x", 0.0, 10.0))
        c.setAlgorithmKind(AlgorithmKind.STOCHASTIC_HILL_CLIMBING)
    }

    // ── Evaluation: preference semantics ───────────────────────────────

    @Test
    fun `setEvaluationSpec marks dirty`() {
        SimoptAppController("Test").use { c ->
            seedRunnableProblem(c)
            c.markSaved(Path.of("/tmp/dummy"))
            assertFalse(c.isDirty.value)
            c.setEvaluationSpec(c.evaluationSpec.value.copy(snapshotFrequency = 5))
            assertTrue(c.isDirty.value)
        }
    }

    @Test
    fun `setEvaluationSpec does not invalidate the previous run's results`() {
        SimoptAppController("Test").use { c ->
            seedRunnableProblem(c)
            // editedSinceLastRun is true after the structural seed.
            // We pin that evaluation-spec edits do NOT *additionally*
            // disturb the document beyond marking dirty.  In O7b
            // there'll be a real lastResult to assert against; for
            // O7a we settle for: the preference path doesn't throw
            // and does set dirty.
            val before = c.editedSinceLastRun.value
            c.setEvaluationSpec(c.evaluationSpec.value.copy(snapshotFrequency = 3))
            // editedSinceLastRun does NOT flip on preference edits.
            assertEquals(before, c.editedSinceLastRun.value)
        }
    }

    @Test
    fun `setEvaluationSpec is a no-op when value equals current`() {
        SimoptAppController("Test").use { c ->
            val before = c.evaluationSpec.value
            c.setEvaluationSpec(before)
            assertEquals(before, c.evaluationSpec.value)
            assertFalse(c.isDirty.value, "Idempotent no-op should not mark dirty")
        }
    }

    @Test
    fun `setEvaluationSpec persists nullable overrides`() {
        SimoptAppController("Test").use { c ->
            c.setEvaluationSpec(EvaluationSpec(
                maxFeasibleSamplingIterations = 25,
                solutionPrecision = 0.0001
            ))
            assertEquals(25, c.evaluationSpec.value.maxFeasibleSamplingIterations)
            assertEquals(0.0001, c.evaluationSpec.value.solutionPrecision)
        }
    }

    // ── Tracking: preference semantics + invariants ────────────────────

    @Test
    fun `setTrackingSpec marks dirty`() {
        SimoptAppController("Test").use { c ->
            seedRunnableProblem(c)
            c.markSaved(Path.of("/tmp/dummy"))
            assertFalse(c.isDirty.value)
            c.setTrackingSpec(c.trackingSpec.value.copy(enableCsvTrace = true))
            assertTrue(c.isDirty.value)
        }
    }

    @Test
    fun `setTrackingSpec does not flip editedSinceLastRun`() {
        SimoptAppController("Test").use { c ->
            seedRunnableProblem(c)
            val before = c.editedSinceLastRun.value
            c.setTrackingSpec(c.trackingSpec.value.copy(enableConsoleTrace = true))
            assertEquals(before, c.editedSinceLastRun.value)
        }
    }

    @Test
    fun `SolverTrackingSpec init rejects blank experimentLabel`() {
        assertThrows<IllegalArgumentException> {
            SolverTrackingSpec(experimentLabel = "")
        }
        assertThrows<IllegalArgumentException> {
            SolverTrackingSpec(experimentLabel = "   ")
        }
    }

    @Test
    fun `SolverTrackingSpec init rejects blank csvFileName when non-null`() {
        assertThrows<IllegalArgumentException> {
            SolverTrackingSpec(csvFileName = "")
        }
    }

    // ── Path-resolution helpers ───────────────────────────────────────

    @Test
    fun `RunSetupPaths outputDir resolves under appWorkspace`(@TempDir tempDir: Path) {
        val out = RunSetupPaths.outputDir(tempDir, "MyAnalysis")
        assertEquals(tempDir.resolve("output").resolve("MyAnalysis"), out)
    }

    @Test
    fun `RunSetupPaths outputDir sanitizes analysis name`(@TempDir tempDir: Path) {
        val out = RunSetupPaths.outputDir(tempDir, "Bad/Name With Spaces")
        assertTrue(out.toString().endsWith("Bad_Name_With_Spaces"),
            "Sanitization should replace unsafe characters; got $out")
    }

    @Test
    fun `RunSetupPaths traceFilePath returns null when tracking is disabled`(@TempDir tempDir: Path) {
        val path = RunSetupPaths.traceFilePath(
            appWorkspace = tempDir,
            analysisName = "MyAnalysis",
            trackingSpec = SolverTrackingSpec(enableCsvTrace = false),
            solverSpec = SolverSpec.StochasticHillClimbing(maxIterations = 1, replicationsPerEvaluation = 1)
        )
        assertNull(path)
    }

    @Test
    fun `RunSetupPaths traceFilePath uses csvFileName when set`(@TempDir tempDir: Path) {
        val path = RunSetupPaths.traceFilePath(
            appWorkspace = tempDir,
            analysisName = "MyAnalysis",
            trackingSpec = SolverTrackingSpec(enableCsvTrace = true, csvFileName = "custom"),
            solverSpec = SolverSpec.StochasticHillClimbing(maxIterations = 1, replicationsPerEvaluation = 1)
        )
        assertNotNull(path)
        assertTrue(path.toString().endsWith("custom.csv"),
            "Expected custom.csv; got $path")
    }

    @Test
    fun `RunSetupPaths traceFilePath falls back to solver name when csvFileName is null`(@TempDir tempDir: Path) {
        val path = RunSetupPaths.traceFilePath(
            appWorkspace = tempDir,
            analysisName = "MyAnalysis",
            trackingSpec = SolverTrackingSpec(enableCsvTrace = true, csvFileName = null),
            solverSpec = SolverSpec.StochasticHillClimbing(
                maxIterations = 1, replicationsPerEvaluation = 1, name = "my-shc"
            )
        )
        assertNotNull(path)
        assertTrue(path.toString().endsWith("my-shc_trace.csv"),
            "Expected my-shc_trace.csv; got $path")
    }

    @Test
    fun `RunSetupPaths traceFilePath falls back to kind label when solver name is null`(@TempDir tempDir: Path) {
        val path = RunSetupPaths.traceFilePath(
            appWorkspace = tempDir,
            analysisName = "MyAnalysis",
            trackingSpec = SolverTrackingSpec(enableCsvTrace = true, csvFileName = null),
            solverSpec = SolverSpec.SimulatedAnnealing(
                maxIterations = 1,
                replicationsPerEvaluation = 1,
                coolingSchedule = ksl.app.config.optimization.CoolingScheduleSpec.Logarithmic(10.0),
                stoppingTemperature = 0.1
            )
        )
        assertNotNull(path)
        assertTrue(path.toString().endsWith("simulatedAnnealing_trace.csv"),
            "Expected simulatedAnnealing_trace.csv; got $path")
    }

    @Test
    fun `RunSetupPaths traceFilePath uses 'solver' fallback when solver spec is null`(@TempDir tempDir: Path) {
        val path = RunSetupPaths.traceFilePath(
            appWorkspace = tempDir,
            analysisName = "MyAnalysis",
            trackingSpec = SolverTrackingSpec(enableCsvTrace = true, csvFileName = null),
            solverSpec = null
        )
        assertNotNull(path)
        assertTrue(path.toString().endsWith("solver_trace.csv"),
            "Expected solver_trace.csv; got $path")
    }

    // ── TOML round-trip ─────────────────────────────────────────────────

    @Test
    fun `loadConfiguration restores evaluation and tracking settings`(@TempDir tempDir: Path) {
        val target = tempDir.resolve("opt.toml")
        SimoptAppController("Test").use { writer ->
            seedRunnableProblem(writer)
            writer.setEvaluationSpec(EvaluationSpec(
                useSolutionCache = false,
                useSimulationRunCache = true,
                snapshotFrequency = 3,
                ensureProblemFeasibleRequests = true,
                maxFeasibleSamplingIterations = 50,
                solutionPrecision = 0.001
            ))
            writer.setTrackingSpec(SolverTrackingSpec(
                enableCsvTrace = true,
                csvFileName = "my-trace",
                enableConsoleTrace = true,
                experimentLabel = "RoundTrip"
            ))
            writer.saveConfiguration(target)
        }
        SimoptAppController("Test").use { reader ->
            val result = reader.loadConfiguration(target)
            assertTrue(result is SimoptAppController.LoadResult.Success)
            assertEquals(3, reader.evaluationSpec.value.snapshotFrequency)
            assertEquals(50, reader.evaluationSpec.value.maxFeasibleSamplingIterations)
            assertEquals(0.001, reader.evaluationSpec.value.solutionPrecision)
            assertTrue(reader.evaluationSpec.value.ensureProblemFeasibleRequests)
            assertEquals("my-trace", reader.trackingSpec.value.csvFileName)
            assertEquals("RoundTrip", reader.trackingSpec.value.experimentLabel)
            assertTrue(reader.trackingSpec.value.enableCsvTrace)
            assertTrue(reader.trackingSpec.value.enableConsoleTrace)
        }
    }
}
