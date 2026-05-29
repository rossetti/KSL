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
import ksl.app.config.optimization.CESamplerSpec
import ksl.app.config.optimization.CoolingScheduleSpec
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.app.config.optimization.OptimizationRunConfigurationToml
import ksl.app.config.optimization.RandomRestartSpec
import ksl.app.config.optimization.SolverSpec
import ksl.app.config.optimization.TemperatureSpec
import ksl.app.swing.simopt.stepper.Step
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
 *  Phase O6 controller-level tests for the decomposed solver-spec
 *  state and the per-piece mutators.  Pure JVM tests — no Swing
 *  constructed.
 */
class SimoptAppAlgorithmTest {

    private val mm1BundleId = MM1Bundle().bundleId
    private val mm1ModelId = MM1Bundle.MODEL_ID
    private fun mm1Ref() = ModelReference.ByBundleAndModelId(mm1BundleId, mm1ModelId)

    private fun seedMinimumProblem(c: SimoptAppController) {
        c.setModelReference(mm1Ref())
        val descriptor = c.currentModelDescriptor.value
        assertNotNull(descriptor)
        c.setObjectiveResponseName(descriptor.responseNames.first())
        c.addInput(OptimizationInputSpec("x", 0.0, 10.0))
    }

    // ── Default state ──────────────────────────────────────────────────

    @Test
    fun `default state has null algorithmKind and null solverSpec`() {
        SimoptAppController("Test").use { c ->
            assertNull(c.algorithmKind.value)
            assertNull(c.solverSpec.value)
        }
    }

    @Test
    fun `defaults pre-populate every algorithm-specific flow`() {
        SimoptAppController("Test").use { c ->
            // Common
            assertEquals(100, c.commonMaxIterations.value)
            assertEquals(0, c.commonStreamNum.value)
            assertNull(c.commonSolverName.value)
            assertEquals(30, c.commonReplicationsPerEvaluation.value)
            // SA
            assertTrue(c.saTemperature.value is TemperatureSpec.AutoCalibrate)
            assertTrue(c.saCoolingSchedule.value is CoolingScheduleSpec.Exponential)
            assertEquals(0.001, c.saStoppingTemperature.value)
            // CE
            assertTrue(c.ceSampler.value is CESamplerSpec.Normal)
            assertEquals(0.1, c.ceElitePct.value)
            assertEquals(50, c.ceSampleSize.value)
            // RSpline
            assertEquals(2, c.rsplineInitialNumReps.value)
            assertEquals(1.5, c.rsplineGrowthRate.value)
            assertEquals(30, c.rsplineMaxNumReplications.value)
            // Restart
            assertNull(c.randomRestart.value)
        }
    }

    // ── Algorithm kind ─────────────────────────────────────────────────

    @Test
    fun `setAlgorithmKind(SHC) publishes a StochasticHillClimbing spec`() {
        SimoptAppController("Test").use { c ->
            c.setAlgorithmKind(AlgorithmKind.STOCHASTIC_HILL_CLIMBING)
            val spec = c.solverSpec.value
            assertTrue(spec is SolverSpec.StochasticHillClimbing,
                "Expected StochasticHillClimbing; got ${spec?.let { it::class.simpleName }}")
            assertEquals(100, spec.maxIterations)
            assertEquals(30, spec.replicationsPerEvaluation)
        }
    }

    @Test
    fun `setAlgorithmKind(SA) publishes a SimulatedAnnealing spec with defaulted nested editors`() {
        SimoptAppController("Test").use { c ->
            c.setAlgorithmKind(AlgorithmKind.SIMULATED_ANNEALING)
            val spec = c.solverSpec.value
            assertTrue(spec is SolverSpec.SimulatedAnnealing)
            assertTrue(spec.temperature is TemperatureSpec.AutoCalibrate)
            assertTrue(spec.coolingSchedule is CoolingScheduleSpec.Exponential)
        }
    }

    @Test
    fun `setAlgorithmKind(CE) publishes a CrossEntropy spec with elitePct and ceSampleSize set`() {
        SimoptAppController("Test").use { c ->
            c.setAlgorithmKind(AlgorithmKind.CROSS_ENTROPY)
            val spec = c.solverSpec.value
            assertTrue(spec is SolverSpec.CrossEntropy)
            assertEquals(0.1, spec.elitePct)
            assertEquals(50, spec.ceSampleSize)
            assertTrue(spec.sampler is CESamplerSpec.Normal)
        }
    }

    @Test
    fun `setAlgorithmKind(RSpline) publishes an RSpline spec with growth-schedule defaults`() {
        SimoptAppController("Test").use { c ->
            c.setAlgorithmKind(AlgorithmKind.R_SPLINE)
            val spec = c.solverSpec.value
            assertTrue(spec is SolverSpec.RSpline)
            assertEquals(2, spec.initialNumReps)
            assertEquals(1.5, spec.sampleSizeGrowthRate)
            assertEquals(30, spec.maxNumReplications)
        }
    }

    @Test
    fun `setAlgorithmKind(null) clears solverSpec`() {
        SimoptAppController("Test").use { c ->
            c.setAlgorithmKind(AlgorithmKind.STOCHASTIC_HILL_CLIMBING)
            assertNotNull(c.solverSpec.value)
            c.setAlgorithmKind(null)
            assertNull(c.solverSpec.value)
        }
    }

    // ── Common parameters preserved across algorithm switches ──────────

    @Test
    fun `switching algorithm kind preserves common parameters`() {
        SimoptAppController("Test").use { c ->
            c.setAlgorithmKind(AlgorithmKind.STOCHASTIC_HILL_CLIMBING)
            c.setCommonMaxIterations(250)
            c.setCommonStreamNum(7)
            c.setCommonSolverName("solver-A")
            c.setCommonReplicationsPerEvaluation(8)

            c.setAlgorithmKind(AlgorithmKind.SIMULATED_ANNEALING)
            val sa = c.solverSpec.value as SolverSpec.SimulatedAnnealing
            assertEquals(250, sa.maxIterations)
            assertEquals(7, sa.streamNum)
            assertEquals("solver-A", sa.name)
            assertEquals(8, sa.replicationsPerEvaluation)
        }
    }

    @Test
    fun `switching to SA and back preserves SA-specific parameters`() {
        SimoptAppController("Test").use { c ->
            c.setAlgorithmKind(AlgorithmKind.SIMULATED_ANNEALING)
            c.setSaTemperature(TemperatureSpec.Fixed(50.0))
            c.setSaCoolingSchedule(CoolingScheduleSpec.Linear(50.0, 0.5, 200))
            c.setSaStoppingTemperature(0.5)

            c.setAlgorithmKind(AlgorithmKind.STOCHASTIC_HILL_CLIMBING)
            assertTrue(c.solverSpec.value is SolverSpec.StochasticHillClimbing)

            c.setAlgorithmKind(AlgorithmKind.SIMULATED_ANNEALING)
            val sa = c.solverSpec.value as SolverSpec.SimulatedAnnealing
            assertTrue(sa.temperature is TemperatureSpec.Fixed)
            assertEquals(50.0, (sa.temperature as TemperatureSpec.Fixed).temperature)
            assertTrue(sa.coolingSchedule is CoolingScheduleSpec.Linear)
            assertEquals(0.5, sa.stoppingTemperature)
        }
    }

    @Test
    fun `random restart preserves across algorithm switches`() {
        SimoptAppController("Test").use { c ->
            c.setAlgorithmKind(AlgorithmKind.STOCHASTIC_HILL_CLIMBING)
            c.setRandomRestart(RandomRestartSpec(maxNumRestarts = 5))
            assertEquals(5, (c.solverSpec.value as SolverSpec.StochasticHillClimbing).randomRestart?.maxNumRestarts)

            c.setAlgorithmKind(AlgorithmKind.CROSS_ENTROPY)
            assertEquals(5, (c.solverSpec.value as SolverSpec.CrossEntropy).randomRestart?.maxNumRestarts)

            c.setAlgorithmKind(AlgorithmKind.R_SPLINE)
            assertEquals(5, (c.solverSpec.value as SolverSpec.RSpline).randomRestart?.maxNumRestarts)
        }
    }

    // ── Validation on mutators ─────────────────────────────────────────

    @Test
    fun `setCommonMaxIterations rejects non-positive values`() {
        SimoptAppController("Test").use { c ->
            assertThrows<IllegalArgumentException> { c.setCommonMaxIterations(0) }
            assertThrows<IllegalArgumentException> { c.setCommonMaxIterations(-5) }
        }
    }

    @Test
    fun `setCeElitePct rejects values outside (0,1)`() {
        SimoptAppController("Test").use { c ->
            assertThrows<IllegalArgumentException> { c.setCeElitePct(0.0) }
            assertThrows<IllegalArgumentException> { c.setCeElitePct(1.0) }
            assertThrows<IllegalArgumentException> { c.setCeElitePct(-0.1) }
        }
    }

    @Test
    fun `setSaStoppingTemperature rejects non-positive or non-finite values`() {
        SimoptAppController("Test").use { c ->
            assertThrows<IllegalArgumentException> { c.setSaStoppingTemperature(0.0) }
            assertThrows<IllegalArgumentException> { c.setSaStoppingTemperature(-1.0) }
            assertThrows<IllegalArgumentException> { c.setSaStoppingTemperature(Double.NaN) }
        }
    }

    // ── setSolverSpec fan-out ──────────────────────────────────────────

    @Test
    fun `setSolverSpec(SHC) fans out into common flows`() {
        SimoptAppController("Test").use { c ->
            val spec = SolverSpec.StochasticHillClimbing(
                maxIterations = 7,
                replicationsPerEvaluation = 4,
                streamNum = 9,
                name = "shc-test"
            )
            c.setSolverSpec(spec)
            assertEquals(AlgorithmKind.STOCHASTIC_HILL_CLIMBING, c.algorithmKind.value)
            assertEquals(7, c.commonMaxIterations.value)
            assertEquals(4, c.commonReplicationsPerEvaluation.value)
            assertEquals(9, c.commonStreamNum.value)
            assertEquals("shc-test", c.commonSolverName.value)
            assertEquals(spec, c.solverSpec.value)
        }
    }

    @Test
    fun `setSolverSpec(SA) fans out into SA-specific flows`() {
        SimoptAppController("Test").use { c ->
            val spec = SolverSpec.SimulatedAnnealing(
                maxIterations = 100,
                replicationsPerEvaluation = 5,
                // Explicit name overrides the controller's auto-derived
                // default (which would otherwise be the algorithm's
                // displayName).  Round-trip equality needs an exact
                // match — we're testing fan-out semantics, not the
                // name-derivation logic (that has its own tests in
                // SimoptAppResultsTest).
                name = "sa-test",
                temperature = TemperatureSpec.Fixed(50.0),
                coolingSchedule = CoolingScheduleSpec.Logarithmic(50.0),
                stoppingTemperature = 0.5
            )
            c.setSolverSpec(spec)
            assertEquals(AlgorithmKind.SIMULATED_ANNEALING, c.algorithmKind.value)
            assertTrue(c.saTemperature.value is TemperatureSpec.Fixed)
            assertTrue(c.saCoolingSchedule.value is CoolingScheduleSpec.Logarithmic)
            assertEquals(0.5, c.saStoppingTemperature.value)
            assertEquals(spec, c.solverSpec.value)
        }
    }

    @Test
    fun `setSolverSpec(CE) fans out and preserves elitePct override`() {
        SimoptAppController("Test").use { c ->
            val spec = SolverSpec.CrossEntropy(
                maxIterations = 200,
                replicationsPerEvaluation = 30,
                // See companion comment in the SA fan-out test.
                name = "ce-test",
                elitePct = 0.05,
                ceSampleSize = 80
            )
            c.setSolverSpec(spec)
            assertEquals(AlgorithmKind.CROSS_ENTROPY, c.algorithmKind.value)
            assertEquals(0.05, c.ceElitePct.value)
            assertEquals(80, c.ceSampleSize.value)
            assertEquals(spec, c.solverSpec.value)
        }
    }

    @Test
    fun `setSolverSpec(RSpline) fans out into RSpline-specific flows`() {
        SimoptAppController("Test").use { c ->
            val spec = SolverSpec.RSpline(
                maxIterations = 50,
                // See companion comment in the SA fan-out test.
                name = "rspline-test",
                initialNumReps = 4,
                sampleSizeGrowthRate = 2.0,
                maxNumReplications = 64
            )
            c.setSolverSpec(spec)
            assertEquals(AlgorithmKind.R_SPLINE, c.algorithmKind.value)
            assertEquals(4, c.rsplineInitialNumReps.value)
            assertEquals(2.0, c.rsplineGrowthRate.value)
            assertEquals(64, c.rsplineMaxNumReplications.value)
            assertEquals(spec, c.solverSpec.value)
        }
    }

    @Test
    fun `setSolverSpec(null) resets every piece to defaults`() {
        SimoptAppController("Test").use { c ->
            c.setSolverSpec(SolverSpec.RSpline(
                maxIterations = 50, initialNumReps = 5,
                sampleSizeGrowthRate = 2.0, maxNumReplications = 50
            ))
            c.setRandomRestart(RandomRestartSpec(3))
            c.setSolverSpec(null)
            assertNull(c.algorithmKind.value)
            assertNull(c.solverSpec.value)
            assertNull(c.randomRestart.value)
            assertEquals(2, c.rsplineInitialNumReps.value)   // back to default
        }
    }

    // ── Step gating ────────────────────────────────────────────────────

    @Test
    fun `Algorithm step completion gates on non-null solverSpec`() {
        SimoptAppController("Test").use { c ->
            seedMinimumProblem(c)
            assertFalse(c.canAdvanceTo(Step.RUN_SETUP),
                "Locked until algorithm is committed")
            c.setAlgorithmKind(AlgorithmKind.STOCHASTIC_HILL_CLIMBING)
            assertTrue(c.canAdvanceTo(Step.RUN_SETUP))
        }
    }

    // ── R1 lifecycle ───────────────────────────────────────────────────

    @Test
    fun `setAlgorithmKind marks dirty and drops lastResult`() {
        SimoptAppController("Test").use { c ->
            seedMinimumProblem(c)
            c.markSaved(Path.of("/tmp/dummy"))
            assertFalse(c.isDirty.value)
            c.setAlgorithmKind(AlgorithmKind.STOCHASTIC_HILL_CLIMBING)
            assertTrue(c.isDirty.value)
        }
    }

    // ── TOML round-trip ────────────────────────────────────────────────

    @Test
    fun `TOML round-trip preserves SHC with random restart`(@TempDir tempDir: Path) {
        roundTripSpec(tempDir, SolverSpec.StochasticHillClimbing(
            maxIterations = 25,
            replicationsPerEvaluation = 3,
            randomRestart = RandomRestartSpec(maxNumRestarts = 4),
            name = "shc-rr"
        ))
    }

    @Test
    fun `TOML round-trip preserves SA with auto-calibrate and exponential cooling`(@TempDir tempDir: Path) {
        roundTripSpec(tempDir, SolverSpec.SimulatedAnnealing(
            maxIterations = 60,
            replicationsPerEvaluation = 4,
            // Explicit name — see fan-out tests above.
            name = "sa-roundtrip",
            temperature = TemperatureSpec.AutoCalibrate(targetProbability = 0.7, sampleSize = 50),
            coolingSchedule = CoolingScheduleSpec.Exponential(initialTemperature = 200.0, coolingRate = 0.9),
            stoppingTemperature = 0.005
        ))
    }

    @Test
    fun `TOML round-trip preserves CE with normal sampler and overrides`(@TempDir tempDir: Path) {
        roundTripSpec(tempDir, SolverSpec.CrossEntropy(
            maxIterations = 75,
            replicationsPerEvaluation = 6,
            // Explicit name — see fan-out tests above.
            name = "ce-roundtrip",
            sampler = CESamplerSpec.Normal(meanSmoother = 0.9, sdSmoother = 0.8),
            elitePct = 0.2,
            ceSampleSize = 40
        ))
    }

    @Test
    fun `TOML round-trip preserves RSpline with random restart`(@TempDir tempDir: Path) {
        roundTripSpec(tempDir, SolverSpec.RSpline(
            maxIterations = 30,
            // Explicit name — see fan-out tests above.
            name = "rspline-roundtrip",
            initialNumReps = 3,
            sampleSizeGrowthRate = 2.5,
            maxNumReplications = 100,
            randomRestart = RandomRestartSpec(maxNumRestarts = 6)
        ))
    }

    private fun roundTripSpec(tempDir: Path, spec: SolverSpec) {
        val target = tempDir.resolve("opt.toml")
        SimoptAppController("Test").use { writer ->
            seedMinimumProblem(writer)
            writer.setSolverSpec(spec)
            writer.saveConfiguration(target)
        }
        SimoptAppController("Test").use { reader ->
            val result = reader.loadConfiguration(target)
            assertTrue(result is SimoptAppController.LoadResult.Success)
            val decoded = OptimizationRunConfigurationToml.decode(target.toFile().readText())
            assertEquals(spec, decoded.solver)
            assertEquals(spec, reader.solverSpec.value)
        }
    }
}
