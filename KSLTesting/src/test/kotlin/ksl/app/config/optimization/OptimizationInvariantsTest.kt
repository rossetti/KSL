package ksl.app.config.optimization

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Phase 5.85 Step 3.5 acceptance: domain invariants in [init] blocks.
 *
 * Each test asserts that constructing a `@Serializable` data class with a
 * domain-violating value throws [IllegalArgumentException].  These tests
 * lock the invariants in so that future edits cannot silently drop them.
 *
 * Round-trip tests (defaults are accepted, valid values survive
 * serialization) live in [OptimizationRunConfigurationTest] and are not
 * duplicated here.
 */
class OptimizationInvariantsTest {

    // ── PenaltyFunctionSpec.WithMemory ───────────────────────────────────────

    @Test fun `WithMemory rejects non-positive basePenalty`() {
        assertThrows<IllegalArgumentException> {
            PenaltyFunctionSpec.WithMemory(basePenalty = 0.0)
        }
        assertThrows<IllegalArgumentException> {
            PenaltyFunctionSpec.WithMemory(basePenalty = -1.0)
        }
    }
    @Test fun `WithMemory rejects non-finite basePenalty`() {
        assertThrows<IllegalArgumentException> {
            PenaltyFunctionSpec.WithMemory(basePenalty = Double.POSITIVE_INFINITY)
        }
        assertThrows<IllegalArgumentException> {
            PenaltyFunctionSpec.WithMemory(basePenalty = Double.NaN)
        }
    }
    @Test fun `WithMemory rejects negative iterationExponent`() {
        assertThrows<IllegalArgumentException> {
            PenaltyFunctionSpec.WithMemory(iterationExponent = -0.1)
        }
    }
    @Test fun `WithMemory rejects non-positive violationExponent`() {
        assertThrows<IllegalArgumentException> {
            PenaltyFunctionSpec.WithMemory(violationExponent = 0.0)
        }
    }

    // ── PenaltyFunctionSpec.DynamicPolynomial ────────────────────────────────

    @Test fun `DynamicPolynomial rejects non-positive basePenalty`() {
        assertThrows<IllegalArgumentException> {
            PenaltyFunctionSpec.DynamicPolynomial(basePenalty = 0.0)
        }
    }
    @Test fun `DynamicPolynomial rejects negative iterationExponent`() {
        assertThrows<IllegalArgumentException> {
            PenaltyFunctionSpec.DynamicPolynomial(iterationExponent = -1.0)
        }
    }
    @Test fun `DynamicPolynomial rejects non-positive violationExponent`() {
        assertThrows<IllegalArgumentException> {
            PenaltyFunctionSpec.DynamicPolynomial(violationExponent = 0.0)
        }
    }

    // ── OptimizationInputSpec ────────────────────────────────────────────────

    @Test fun `OptimizationInputSpec rejects blank name`() {
        assertThrows<IllegalArgumentException> {
            OptimizationInputSpec(name = "  ", lowerBound = 0.0, upperBound = 1.0)
        }
    }
    @Test fun `OptimizationInputSpec rejects non-finite bounds`() {
        assertThrows<IllegalArgumentException> {
            OptimizationInputSpec(name = "x", lowerBound = Double.NEGATIVE_INFINITY, upperBound = 1.0)
        }
        assertThrows<IllegalArgumentException> {
            OptimizationInputSpec(name = "x", lowerBound = 0.0, upperBound = Double.NaN)
        }
    }
    @Test fun `OptimizationInputSpec rejects lowerBound greater than or equal to upperBound`() {
        assertThrows<IllegalArgumentException> {
            OptimizationInputSpec(name = "x", lowerBound = 5.0, upperBound = 5.0)
        }
        assertThrows<IllegalArgumentException> {
            OptimizationInputSpec(name = "x", lowerBound = 10.0, upperBound = 1.0)
        }
    }
    @Test fun `OptimizationInputSpec rejects negative or non-finite granularity`() {
        assertThrows<IllegalArgumentException> {
            OptimizationInputSpec(name = "x", lowerBound = 0.0, upperBound = 1.0, granularity = -0.5)
        }
        assertThrows<IllegalArgumentException> {
            OptimizationInputSpec(name = "x", lowerBound = 0.0, upperBound = 1.0, granularity = Double.POSITIVE_INFINITY)
        }
    }

    // ── LinearConstraintSpec ─────────────────────────────────────────────────

    @Test fun `LinearConstraintSpec rejects empty coefficients`() {
        assertThrows<IllegalArgumentException> {
            LinearConstraintSpec(coefficients = emptyMap())
        }
    }
    @Test fun `LinearConstraintSpec rejects blank coefficient keys`() {
        assertThrows<IllegalArgumentException> {
            LinearConstraintSpec(coefficients = mapOf("" to 1.0))
        }
    }
    @Test fun `LinearConstraintSpec rejects non-finite coefficient values`() {
        assertThrows<IllegalArgumentException> {
            LinearConstraintSpec(coefficients = mapOf("x" to Double.POSITIVE_INFINITY))
        }
    }
    @Test fun `LinearConstraintSpec rejects non-finite rhsValue`() {
        assertThrows<IllegalArgumentException> {
            LinearConstraintSpec(coefficients = mapOf("x" to 1.0), rhsValue = Double.NaN)
        }
    }

    // ── ResponseConstraintSpec ───────────────────────────────────────────────

    @Test fun `ResponseConstraintSpec rejects blank name`() {
        assertThrows<IllegalArgumentException> {
            ResponseConstraintSpec(name = "", rhsValue = 1.0)
        }
    }
    @Test fun `ResponseConstraintSpec rejects non-finite rhsValue or target`() {
        assertThrows<IllegalArgumentException> {
            ResponseConstraintSpec(name = "r", rhsValue = Double.POSITIVE_INFINITY)
        }
        assertThrows<IllegalArgumentException> {
            ResponseConstraintSpec(name = "r", rhsValue = 1.0, target = Double.NaN)
        }
    }
    @Test fun `ResponseConstraintSpec rejects negative or non-finite tolerance`() {
        assertThrows<IllegalArgumentException> {
            ResponseConstraintSpec(name = "r", rhsValue = 1.0, tolerance = -0.1)
        }
        assertThrows<IllegalArgumentException> {
            ResponseConstraintSpec(name = "r", rhsValue = 1.0, tolerance = Double.POSITIVE_INFINITY)
        }
    }

    // ── OptimizationProblemSpec ──────────────────────────────────────────────

    private fun oneInput() = listOf(
        OptimizationInputSpec(name = "x", lowerBound = 0.0, upperBound = 1.0)
    )

    @Test fun `OptimizationProblemSpec rejects blank objectiveResponseName`() {
        assertThrows<IllegalArgumentException> {
            OptimizationProblemSpec(objectiveResponseName = "", inputs = oneInput())
        }
    }
    @Test fun `OptimizationProblemSpec rejects empty inputs`() {
        assertThrows<IllegalArgumentException> {
            OptimizationProblemSpec(objectiveResponseName = "Cost", inputs = emptyList())
        }
    }
    @Test fun `OptimizationProblemSpec rejects duplicate input names`() {
        assertThrows<IllegalArgumentException> {
            OptimizationProblemSpec(
                objectiveResponseName = "Cost",
                inputs = listOf(
                    OptimizationInputSpec("x", 0.0, 1.0),
                    OptimizationInputSpec("x", 0.0, 1.0)
                )
            )
        }
    }
    @Test fun `OptimizationProblemSpec rejects duplicate or blank responseNames`() {
        assertThrows<IllegalArgumentException> {
            OptimizationProblemSpec(
                objectiveResponseName = "Cost",
                inputs = oneInput(),
                responseNames = listOf("a", "a")
            )
        }
        assertThrows<IllegalArgumentException> {
            OptimizationProblemSpec(
                objectiveResponseName = "Cost",
                inputs = oneInput(),
                responseNames = listOf("a", "")
            )
        }
    }
    @Test fun `OptimizationProblemSpec rejects negative or non-finite indifferenceZoneParameter`() {
        assertThrows<IllegalArgumentException> {
            OptimizationProblemSpec(
                objectiveResponseName = "Cost",
                inputs = oneInput(),
                indifferenceZoneParameter = -0.001
            )
        }
        assertThrows<IllegalArgumentException> {
            OptimizationProblemSpec(
                objectiveResponseName = "Cost",
                inputs = oneInput(),
                indifferenceZoneParameter = Double.POSITIVE_INFINITY
            )
        }
    }
    @Test fun `OptimizationProblemSpec rejects blank optional names`() {
        assertThrows<IllegalArgumentException> {
            OptimizationProblemSpec(
                problemName = "  ",
                objectiveResponseName = "Cost",
                inputs = oneInput()
            )
        }
        assertThrows<IllegalArgumentException> {
            OptimizationProblemSpec(
                modelIdentifier = "",
                objectiveResponseName = "Cost",
                inputs = oneInput()
            )
        }
    }

    // ── EvaluationSpec ───────────────────────────────────────────────────────

    @Test fun `EvaluationSpec rejects non-positive snapshotFrequency`() {
        assertThrows<IllegalArgumentException> { EvaluationSpec(snapshotFrequency = 0) }
        assertThrows<IllegalArgumentException> { EvaluationSpec(snapshotFrequency = -1) }
    }
    @Test fun `EvaluationSpec rejects non-positive maxFeasibleSamplingIterations when non-null`() {
        assertThrows<IllegalArgumentException> {
            EvaluationSpec(maxFeasibleSamplingIterations = 0)
        }
    }
    @Test fun `EvaluationSpec rejects non-positive or non-finite solutionPrecision when non-null`() {
        assertThrows<IllegalArgumentException> { EvaluationSpec(solutionPrecision = 0.0) }
        assertThrows<IllegalArgumentException> {
            EvaluationSpec(solutionPrecision = Double.POSITIVE_INFINITY)
        }
    }

    // ── RandomRestartSpec ────────────────────────────────────────────────────

    @Test fun `RandomRestartSpec rejects non-positive maxNumRestarts`() {
        assertThrows<IllegalArgumentException> { RandomRestartSpec(maxNumRestarts = 0) }
        assertThrows<IllegalArgumentException> { RandomRestartSpec(maxNumRestarts = -3) }
    }

    // ── TemperatureSpec ──────────────────────────────────────────────────────

    @Test fun `TemperatureSpec_Fixed rejects non-positive or non-finite temperature`() {
        assertThrows<IllegalArgumentException> { TemperatureSpec.Fixed(temperature = 0.0) }
        assertThrows<IllegalArgumentException> {
            TemperatureSpec.Fixed(temperature = Double.POSITIVE_INFINITY)
        }
    }
    @Test fun `TemperatureSpec_AutoCalibrate rejects targetProbability outside open zero one`() {
        assertThrows<IllegalArgumentException> {
            TemperatureSpec.AutoCalibrate(targetProbability = 0.0)
        }
        assertThrows<IllegalArgumentException> {
            TemperatureSpec.AutoCalibrate(targetProbability = 1.0)
        }
    }
    @Test fun `TemperatureSpec_AutoCalibrate rejects non-positive sampleSize`() {
        assertThrows<IllegalArgumentException> {
            TemperatureSpec.AutoCalibrate(sampleSize = 0)
        }
    }

    // ── CoolingScheduleSpec ──────────────────────────────────────────────────

    @Test fun `CoolingScheduleSpec_Linear rejects stoppingTemperature greater than or equal to initialTemperature`() {
        assertThrows<IllegalArgumentException> {
            CoolingScheduleSpec.Linear(initialTemperature = 100.0, stoppingTemperature = 100.0, maxIterations = 10)
        }
        assertThrows<IllegalArgumentException> {
            CoolingScheduleSpec.Linear(initialTemperature = 50.0, stoppingTemperature = 100.0, maxIterations = 10)
        }
    }
    @Test fun `CoolingScheduleSpec_Linear rejects non-positive maxIterations`() {
        assertThrows<IllegalArgumentException> {
            CoolingScheduleSpec.Linear(initialTemperature = 100.0, stoppingTemperature = 1.0, maxIterations = 0)
        }
    }
    @Test fun `CoolingScheduleSpec_Exponential rejects coolingRate outside open zero one`() {
        assertThrows<IllegalArgumentException> {
            CoolingScheduleSpec.Exponential(initialTemperature = 100.0, coolingRate = 0.0)
        }
        assertThrows<IllegalArgumentException> {
            CoolingScheduleSpec.Exponential(initialTemperature = 100.0, coolingRate = 1.0)
        }
    }
    @Test fun `CoolingScheduleSpec_Logarithmic rejects non-positive or non-finite initialTemperature`() {
        assertThrows<IllegalArgumentException> {
            CoolingScheduleSpec.Logarithmic(initialTemperature = 0.0)
        }
        assertThrows<IllegalArgumentException> {
            CoolingScheduleSpec.Logarithmic(initialTemperature = Double.POSITIVE_INFINITY)
        }
    }

    // ── CESamplerSpec.Normal ─────────────────────────────────────────────────

    @Test fun `CESamplerSpec_Normal rejects smoothers outside half-open zero one`() {
        assertThrows<IllegalArgumentException> { CESamplerSpec.Normal(meanSmoother = 0.0) }
        assertThrows<IllegalArgumentException> { CESamplerSpec.Normal(meanSmoother = 1.5) }
        assertThrows<IllegalArgumentException> { CESamplerSpec.Normal(sdSmoother = 0.0) }
    }

    // ── SolverSpec variants ──────────────────────────────────────────────────

    @Test fun `StochasticHillClimbing rejects non-positive maxIterations`() {
        assertThrows<IllegalArgumentException> {
            SolverSpec.StochasticHillClimbing(maxIterations = 0, replicationsPerEvaluation = 1)
        }
    }
    @Test fun `StochasticHillClimbing rejects non-positive replicationsPerEvaluation`() {
        assertThrows<IllegalArgumentException> {
            SolverSpec.StochasticHillClimbing(maxIterations = 10, replicationsPerEvaluation = 0)
        }
    }
    @Test fun `StochasticHillClimbing rejects negative streamNum`() {
        assertThrows<IllegalArgumentException> {
            SolverSpec.StochasticHillClimbing(maxIterations = 10, replicationsPerEvaluation = 1, streamNum = -1)
        }
    }
    @Test fun `SimulatedAnnealing rejects non-positive stoppingTemperature`() {
        assertThrows<IllegalArgumentException> {
            SolverSpec.SimulatedAnnealing(
                maxIterations = 10,
                replicationsPerEvaluation = 1,
                coolingSchedule = CoolingScheduleSpec.Exponential(initialTemperature = 100.0),
                stoppingTemperature = 0.0
            )
        }
    }
    @Test fun `CrossEntropy rejects elitePct outside open zero one when non-null`() {
        assertThrows<IllegalArgumentException> {
            SolverSpec.CrossEntropy(maxIterations = 10, replicationsPerEvaluation = 1, elitePct = 0.0)
        }
        assertThrows<IllegalArgumentException> {
            SolverSpec.CrossEntropy(maxIterations = 10, replicationsPerEvaluation = 1, elitePct = 1.0)
        }
    }
    @Test fun `CrossEntropy rejects ceSampleSize less than one when non-null`() {
        assertThrows<IllegalArgumentException> {
            SolverSpec.CrossEntropy(maxIterations = 10, replicationsPerEvaluation = 1, ceSampleSize = 0)
        }
    }
    @Test fun `RSpline rejects non-positive initialNumReps`() {
        assertThrows<IllegalArgumentException> {
            SolverSpec.RSpline(
                maxIterations = 10,
                initialNumReps = 0,
                sampleSizeGrowthRate = 1.1,
                maxNumReplications = 100
            )
        }
    }
    @Test fun `RSpline rejects non-positive or non-finite sampleSizeGrowthRate`() {
        assertThrows<IllegalArgumentException> {
            SolverSpec.RSpline(
                maxIterations = 10,
                initialNumReps = 1,
                sampleSizeGrowthRate = 0.0,
                maxNumReplications = 100
            )
        }
        assertThrows<IllegalArgumentException> {
            SolverSpec.RSpline(
                maxIterations = 10,
                initialNumReps = 1,
                sampleSizeGrowthRate = Double.POSITIVE_INFINITY,
                maxNumReplications = 100
            )
        }
    }
    @Test fun `RSpline rejects maxNumReplications less than initialNumReps`() {
        assertThrows<IllegalArgumentException> {
            SolverSpec.RSpline(
                maxIterations = 10,
                initialNumReps = 50,
                sampleSizeGrowthRate = 1.1,
                maxNumReplications = 10
            )
        }
    }
}
