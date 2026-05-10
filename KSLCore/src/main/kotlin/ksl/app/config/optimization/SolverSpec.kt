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

package ksl.app.config.optimization

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Optional random-restart wrapper for any [SolverSpec] variant.
 *
 * When non-null on a [SolverSpec], the optimization solver factory (Step 6)
 * will wrap the chosen algorithm in a
 * [ksl.simopt.solvers.algorithms.RandomRestartSolver] performing at most
 * [maxNumRestarts] restarts from randomly drawn starting points.
 *
 * Modeling restart as data on the base sealed class (rather than as
 * additional `RandomRestartX` sealed variants) keeps the sealed hierarchy
 * focused on algorithm choice and lets every algorithm be wrapped
 * uniformly.
 *
 * @property maxNumRestarts maximum number of restarts; must be positive when
 *           validated
 */
@Serializable
data class RandomRestartSpec(val maxNumRestarts: Int)

/**
 * Strategy for choosing the initial temperature of a simulated-annealing
 * solver.
 *
 * Mirrors [ksl.simopt.solvers.algorithms.TemperatureConfiguration].  Two
 * options are supported: a [Fixed] starting temperature, or
 * [AutoCalibrate] that performs a brief random walk during initialization to
 * estimate a temperature that yields the requested initial acceptance
 * probability.
 */
@Serializable
sealed class TemperatureSpec {

    /**
     * Use a fixed starting temperature.
     *
     * @property temperature must be strictly positive when validated
     */
    @Serializable
    @SerialName("fixed")
    data class Fixed(val temperature: Double) : TemperatureSpec()

    /**
     * Estimate the initial temperature by random-walk calibration.
     *
     * @property targetProbability desired initial probability of accepting a
     *           worse solution; must lie strictly within (0, 1) when
     *           validated
     * @property sampleSize number of random-walk steps used to estimate the
     *           cost landscape; must be positive when validated
     */
    @Serializable
    @SerialName("autoCalibrate")
    data class AutoCalibrate(
        val targetProbability: Double = 0.8,
        val sampleSize: Int = 100
    ) : TemperatureSpec()
}

/**
 * Cooling-schedule selection for a simulated-annealing solver.
 *
 * Mirrors the three [ksl.simopt.solvers.algorithms.CoolingScheduleIfc]
 * implementations: linear, exponential, and logarithmic cooling.
 *
 * The `initialTemperature` field on each variant is the temperature at
 * iteration 0; for an [Exponential] schedule the temperature at iteration
 * `i` is `initialTemperature * coolingRate^i`.
 */
@Serializable
sealed class CoolingScheduleSpec {

    /** Linear cooling between [initialTemperature] and [stoppingTemperature]
     *  over [maxIterations] iterations. */
    @Serializable
    @SerialName("linear")
    data class Linear(
        val initialTemperature: Double,
        val stoppingTemperature: Double,
        val maxIterations: Int
    ) : CoolingScheduleSpec()

    /** Geometric cooling: temperature at iteration `i` is
     *  `initialTemperature * coolingRate^i`. */
    @Serializable
    @SerialName("exponential")
    data class Exponential(
        val initialTemperature: Double,
        val coolingRate: Double = 0.95
    ) : CoolingScheduleSpec()

    /** Logarithmic cooling; very slow but with theoretical convergence
     *  guarantees. */
    @Serializable
    @SerialName("logarithmic")
    data class Logarithmic(val initialTemperature: Double) : CoolingScheduleSpec()
}

/**
 * Cross-entropy sampler selection.
 *
 * The CE algorithm parameterizes a sampling distribution and updates it
 * each iteration based on the elite sample.  This sealed type lets future
 * sampler implementations be added without breaking existing
 * [SolverSpec.CrossEntropy] documents.  In Step 3 only the multivariate
 * normal sampler is exposed.
 */
@Serializable
sealed class CESamplerSpec {

    /**
     * Multivariate-normal cross-entropy sampler; mirrors
     * [ksl.simopt.solvers.algorithms.CENormalSampler].
     *
     * @property meanSmoother exponential-smoothing weight applied to the
     *           mean estimate
     * @property sdSmoother exponential-smoothing weight applied to the
     *           standard-deviation estimate
     * @property coefficientOfVariationThreshold convergence threshold on the
     *           coefficient of variation of the sampling distribution
     * @property streamNum random-number stream number; 0 means "next
     *           available stream"
     */
    @Serializable
    @SerialName("normal")
    data class Normal(
        val meanSmoother: Double = 0.85,
        val sdSmoother: Double = 0.85,
        val coefficientOfVariationThreshold: Double = 0.03,
        val streamNum: Int = 0
    ) : CESamplerSpec()
}

/**
 * Serializable selection of a simulation-optimization solver and its
 * algorithm-specific parameters.
 *
 * Each sealed variant captures the constructor parameters that are plain
 * data; non-serializable arguments to the live solver constructors
 * (evaluator, stream provider, problem definition, equality checkers,
 * functional sampler/elite-size hooks) are supplied by the optimization
 * solver factory in Step 6 and are not part of the persisted document.
 *
 * Sealed-class polymorphic serialization is used: the JSON/TOML output
 * carries a `"type"` discriminator with values `"stochasticHillClimbing"`,
 * `"simulatedAnnealing"`, `"crossEntropy"`, or `"rSpline"`.
 *
 * Per the Step 3 design note "option (a)", random-restart is **not** a
 * separate sealed variant.  Any algorithm can be wrapped by setting
 * [randomRestart] on its variant; setting it to `null` (the default)
 * disables the wrapper.
 */
@Serializable
sealed class SolverSpec {

    /** Optional starting point for the search, keyed by decision-variable
     *  name.  When `null`, the solver chooses its own starting point. */
    abstract val startingPoint: Map<String, Double>?

    /** Maximum number of main-loop iterations the solver is permitted to
     *  run; must be positive when validated. */
    abstract val maxIterations: Int

    /** Optional random-restart wrapper.  When non-null the solver factory
     *  wraps the chosen algorithm in a
     *  [ksl.simopt.solvers.algorithms.RandomRestartSolver]. */
    abstract val randomRestart: RandomRestartSpec?

    /** Random-number stream number used to seed the solver's stochastic
     *  decisions.  0 means "next available stream". */
    abstract val streamNum: Int

    /** Optional human-readable solver instance name. */
    abstract val name: String?

    /** Stochastic Hill Climbing.  Mirrors
     *  [ksl.simopt.solvers.algorithms.StochasticHillClimber]. */
    @Serializable
    @SerialName("stochasticHillClimbing")
    data class StochasticHillClimbing(
        override val startingPoint: Map<String, Double>? = null,
        override val maxIterations: Int,
        override val randomRestart: RandomRestartSpec? = null,
        override val streamNum: Int = 0,
        override val name: String? = null,
        val replicationsPerEvaluation: Int
    ) : SolverSpec()

    /** Simulated Annealing.  Mirrors
     *  [ksl.simopt.solvers.algorithms.SimulatedAnnealing]. */
    @Serializable
    @SerialName("simulatedAnnealing")
    data class SimulatedAnnealing(
        override val startingPoint: Map<String, Double>? = null,
        override val maxIterations: Int,
        override val randomRestart: RandomRestartSpec? = null,
        override val streamNum: Int = 0,
        override val name: String? = null,
        val replicationsPerEvaluation: Int,
        val temperature: TemperatureSpec = TemperatureSpec.AutoCalibrate(),
        val coolingSchedule: CoolingScheduleSpec,
        val stoppingTemperature: Double
    ) : SolverSpec()

    /** Cross-Entropy.  Mirrors
     *  [ksl.simopt.solvers.algorithms.CrossEntropySolver].
     *
     *  [elitePct] and [ceSampleSize] use `null` to mean "let the solver
     *  pick its built-in default" so persisted documents do not bake in the
     *  current default values.  Non-null values override the defaults when
     *  the factory builds the solver. */
    @Serializable
    @SerialName("crossEntropy")
    data class CrossEntropy(
        override val startingPoint: Map<String, Double>? = null,
        override val maxIterations: Int,
        override val randomRestart: RandomRestartSpec? = null,
        override val streamNum: Int = 0,
        override val name: String? = null,
        val replicationsPerEvaluation: Int,
        val sampler: CESamplerSpec = CESamplerSpec.Normal(),
        val elitePct: Double? = null,
        val ceSampleSize: Int? = null
    ) : SolverSpec()

    /** R-SPLINE.  Mirrors
     *  [ksl.simopt.solvers.algorithms.RSplineSolver].
     *
     *  R-SPLINE drives replications per evaluation through a
     *  [ksl.simopt.solvers.FixedGrowthRateReplicationSchedule] rather than a
     *  fixed-count, so this variant has no `replicationsPerEvaluation: Int`
     *  field.  The growth schedule is configured by [initialNumReps],
     *  [sampleSizeGrowthRate], and [maxNumReplications]. */
    @Serializable
    @SerialName("rSpline")
    data class RSpline(
        override val startingPoint: Map<String, Double>? = null,
        override val maxIterations: Int,
        override val randomRestart: RandomRestartSpec? = null,
        override val streamNum: Int = 0,
        override val name: String? = null,
        val initialNumReps: Int,
        val sampleSizeGrowthRate: Double,
        val maxNumReplications: Int
    ) : SolverSpec()
}
