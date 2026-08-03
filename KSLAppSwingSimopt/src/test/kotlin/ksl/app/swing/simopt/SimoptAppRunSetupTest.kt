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
import ksl.app.config.optimization.AlgorithmKind
import ksl.app.config.optimization.SolverTrackingSpec
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
 *  the path-helper integration via the controller, and TOML
 *  round-trip of both spec types.
 */
class SimoptAppRunSetupTest {

    @TempDir
    lateinit var bundleDir: Path

    /** MM1 model bundle JAR, assembled once per test. */
    private val mm1Jar: Path by lazy { SimoptBundleFixtures.mm1Jar(bundleDir) }

    /** A fresh controller backed by an injected library holding the MM1 bundle.
     *  Each call builds its own library; the controller closes it on `use {}` exit. */
    private fun controller() =
        SimoptAppController("Test", injectedBundleLibrary = SimoptBundleFixtures.library(mm1Jar))

    private val mm1BundleId = SimoptBundleFixtures.MM1_BUNDLE_ID
    private val mm1ModelId = SimoptBundleFixtures.MM1_MODEL_ID
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
        controller().use { c ->
            seedRunnableProblem(c)
            c.markSaved(Path.of("/tmp/dummy"))
            assertFalse(c.isDirty.value)
            c.setEvaluationSpec(c.evaluationSpec.value.copy(snapshotFrequency = 5))
            assertTrue(c.isDirty.value)
        }
    }

    @Test
    fun `setEvaluationSpec does not invalidate the previous run's results`() {
        controller().use { c ->
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
        controller().use { c ->
            val before = c.evaluationSpec.value
            c.setEvaluationSpec(before)
            assertEquals(before, c.evaluationSpec.value)
            assertFalse(c.isDirty.value, "Idempotent no-op should not mark dirty")
        }
    }

    @Test
    fun `setEvaluationSpec persists nullable overrides`() {
        controller().use { c ->
            c.setEvaluationSpec(EvaluationSpec(
                maxFeasibleSamplingIterations = 25,
                solutionPrecision = 0.0001
            ))
            assertEquals(25, c.evaluationSpec.value.maxFeasibleSamplingIterations)
            assertEquals(0.0001, c.evaluationSpec.value.solutionPrecision)
        }
    }

    @Test
    fun `setEvaluationSpec persists parallel evaluation settings`() {
        controller().use { c ->
            c.setEvaluationSpec(EvaluationSpec(
                parallelEvaluation = true,
                numEvaluationWorkers = 4
            ))
            assertTrue(c.evaluationSpec.value.parallelEvaluation)
            assertEquals(4, c.evaluationSpec.value.numEvaluationWorkers)
        }
    }

    @Test
    fun `a new document defaults to parallel evaluation`() {
        controller().use { c ->
            assertTrue(
                c.evaluationSpec.value.parallelEvaluation,
                "the Run Step tab should default to parallel evaluation for a new document"
            )
        }
    }

    // ── Tracking: preference semantics + invariants ────────────────────

    @Test
    fun `setTrackingSpec marks dirty`() {
        controller().use { c ->
            seedRunnableProblem(c)
            c.markSaved(Path.of("/tmp/dummy"))
            assertFalse(c.isDirty.value)
            c.setTrackingSpec(c.trackingSpec.value.copy(enableCsvTrace = true))
            assertTrue(c.isDirty.value)
        }
    }

    @Test
    fun `setTrackingSpec does not flip editedSinceLastRun`() {
        controller().use { c ->
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

    // Path-helper tests live in KSLApp at
    // `ksl/app/optimization/paths/OptimizationPathsTest.kt` — they
    // exercise the pure `OptimizationPaths` substrate functions
    // without any controller / Swing dependency.

    // ── TOML round-trip ─────────────────────────────────────────────────

    @Test
    fun `loadConfiguration restores evaluation and tracking settings`(@TempDir tempDir: Path) {
        val target = tempDir.resolve("opt.toml")
        controller().use { writer ->
            seedRunnableProblem(writer)
            writer.setEvaluationSpec(EvaluationSpec(
                useSolutionCache = false,
                useSimulationRunCache = true,
                snapshotFrequency = 3,
                ensureProblemFeasibleRequests = true,
                maxFeasibleSamplingIterations = 50,
                solutionPrecision = 0.001,
                parallelEvaluation = true,
                numEvaluationWorkers = 4
            ))
            writer.setTrackingSpec(SolverTrackingSpec(
                enableCsvTrace = true,
                csvFileName = "my-trace",
                enableConsoleTrace = true,
                experimentLabel = "RoundTrip"
            ))
            writer.saveConfiguration(target)
        }
        controller().use { reader ->
            val result = reader.loadConfiguration(target)
            assertTrue(result is SimoptAppController.LoadResult.Success)
            assertEquals(3, reader.evaluationSpec.value.snapshotFrequency)
            assertEquals(50, reader.evaluationSpec.value.maxFeasibleSamplingIterations)
            assertEquals(0.001, reader.evaluationSpec.value.solutionPrecision)
            assertTrue(reader.evaluationSpec.value.ensureProblemFeasibleRequests)
            assertTrue(reader.evaluationSpec.value.parallelEvaluation)
            assertEquals(4, reader.evaluationSpec.value.numEvaluationWorkers)
            assertEquals("my-trace", reader.trackingSpec.value.csvFileName)
            assertEquals("RoundTrip", reader.trackingSpec.value.experimentLabel)
            assertTrue(reader.trackingSpec.value.enableCsvTrace)
            assertTrue(reader.trackingSpec.value.enableConsoleTrace)
        }
    }
}
