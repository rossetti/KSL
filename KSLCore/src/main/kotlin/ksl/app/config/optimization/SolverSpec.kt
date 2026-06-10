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
import net.peanuuutz.tomlkt.TomlComment

/**
 * Optional random-restart wrapper for any [SolverSpec] variant.
 *
 * When non-null on a [SolverSpec], `OptimizationSolverFactory` wraps the
 * chosen algorithm in a [ksl.simopt.solvers.algorithms.RandomRestartSolver]
 * performing at most [maxNumRestarts] restarts from randomly drawn starting
 * points.
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
data class RandomRestartSpec(
    @TomlComment(
        "Integer. Maximum number of random restarts the wrapper performs\n" +
        "from independently-drawn starting points.  Must be > 0."
    )
    val maxNumRestarts: Int
) {
    init {
        require(maxNumRestarts > 0) {
            "maxNumRestarts must be > 0; was $maxNumRestarts"
        }
    }
}

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
    data class Fixed(
        @TomlComment(
            "Number. Starting temperature.  Must be > 0 and finite."
        )
        val temperature: Double
    ) : TemperatureSpec() {
        init {
            require(temperature > 0.0 && temperature.isFinite()) {
                "temperature must be > 0 and finite; was $temperature"
            }
        }
    }

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
        @TomlComment(
            "Number. Desired initial probability of accepting a worse\n" +
            "solution; the calibration walk targets this value.  Must\n" +
            "lie strictly in (0, 1).  Default: 0.8."
        )
        val targetProbability: Double = 0.8,

        @TomlComment(
            "Integer. Number of random-walk steps used to estimate the\n" +
            "cost landscape during calibration.  Must be > 0.\n" +
            "Default: 100."
        )
        val sampleSize: Int = 100
    ) : TemperatureSpec() {
        init {
            require(targetProbability > 0.0 && targetProbability < 1.0) {
                "targetProbability must be strictly in (0, 1); was $targetProbability"
            }
            require(sampleSize > 0) {
                "sampleSize must be > 0; was $sampleSize"
            }
        }
    }
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
        @TomlComment(
            "Number. Temperature at iteration 0.  Must be > 0 and finite."
        )
        val initialTemperature: Double,

        @TomlComment(
            "Number. Temperature at the final iteration.  Must be > 0,\n" +
            "finite, and strictly less than initialTemperature."
        )
        val stoppingTemperature: Double,

        @TomlComment(
            "Integer. Number of iterations over which temperature decays\n" +
            "linearly from initialTemperature to stoppingTemperature.\n" +
            "Must be > 0."
        )
        val maxIterations: Int
    ) : CoolingScheduleSpec() {
        init {
            require(initialTemperature > 0.0 && initialTemperature.isFinite()) {
                "initialTemperature must be > 0 and finite; was $initialTemperature"
            }
            require(stoppingTemperature > 0.0 && stoppingTemperature.isFinite()) {
                "stoppingTemperature must be > 0 and finite; was $stoppingTemperature"
            }
            require(stoppingTemperature < initialTemperature) {
                "stoppingTemperature ($stoppingTemperature) must be strictly less than initialTemperature ($initialTemperature)"
            }
            require(maxIterations > 0) {
                "maxIterations must be > 0; was $maxIterations"
            }
        }
    }

    /** Geometric cooling: temperature at iteration `i` is
     *  `initialTemperature * coolingRate^i`. */
    @Serializable
    @SerialName("exponential")
    data class Exponential(
        @TomlComment(
            "Number. Temperature at iteration 0.  Must be > 0 and finite."
        )
        val initialTemperature: Double,

        @TomlComment(
            "Number. Geometric decay rate; temperature at iteration i is\n" +
            "initialTemperature * coolingRate^i.  Must lie strictly in\n" +
            "(0, 1).  Default: 0.95."
        )
        val coolingRate: Double = 0.95
    ) : CoolingScheduleSpec() {
        init {
            require(initialTemperature > 0.0 && initialTemperature.isFinite()) {
                "initialTemperature must be > 0 and finite; was $initialTemperature"
            }
            require(coolingRate > 0.0 && coolingRate < 1.0) {
                "coolingRate must be strictly in (0, 1); was $coolingRate"
            }
        }
    }

    /** Logarithmic cooling; very slow but with theoretical convergence
     *  guarantees. */
    @Serializable
    @SerialName("logarithmic")
    data class Logarithmic(
        @TomlComment(
            "Number. Temperature at iteration 0.  Must be > 0 and finite.\n" +
            "Logarithmic cooling is very slow; convergence guarantees are\n" +
            "theoretical."
        )
        val initialTemperature: Double
    ) : CoolingScheduleSpec() {
        init {
            require(initialTemperature > 0.0 && initialTemperature.isFinite()) {
                "initialTemperature must be > 0 and finite; was $initialTemperature"
            }
        }
    }
}

/**
 * Cross-entropy sampler selection.
 *
 * The CE algorithm parameterizes a sampling distribution and updates it
 * each iteration based on the elite sample.  This sealed type lets future
 * sampler implementations be added without breaking existing
 * [SolverSpec.CrossEntropy] documents.  Currently only the multivariate
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
     * @property streamNum ignored. Cross-Entropy uses the single solver-level stream number
     *           (SolverSpec.streamNum); the sampler is attached by the solver onto a distinct stream.
     *           Retained (and still validated as >= 0) so existing persisted documents keep
     *           deserializing.
     */
    @Serializable
    @SerialName("normal")
    data class Normal(
        @TomlComment(
            "Number. Exponential-smoothing weight applied to the mean\n" +
            "estimate (α_μ).  Must lie in (0, 1].  Default: 0.85."
        )
        val meanSmoother: Double = 0.85,

        @TomlComment(
            "Number. Exponential-smoothing weight applied to the standard-\n" +
            "deviation estimate (α_σ).  Must lie in (0, 1].\n" +
            "Default: 0.85."
        )
        val sdSmoother: Double = 0.85,

        @TomlComment(
            "Number. Convergence threshold on the coefficient of variation\n" +
            "of the sampling distribution.  Must be > 0 and finite.\n" +
            "Default: 0.03."
        )
        val coefficientOfVariationThreshold: Double = 0.03,

        @TomlComment(
            "Integer. Ignored: Cross-Entropy uses the single solver-level streamNum, and the\n" +
            "sampler is attached by the solver onto a distinct stream.  Retained for backward\n" +
            "compatibility with existing documents; must be >= 0."
        )
        val streamNum: Int = 0
    ) : CESamplerSpec() {
        init {
            require(meanSmoother > 0.0 && meanSmoother <= 1.0) {
                "meanSmoother must be in (0, 1]; was $meanSmoother"
            }
            require(sdSmoother > 0.0 && sdSmoother <= 1.0) {
                "sdSmoother must be in (0, 1]; was $sdSmoother"
            }
            require(coefficientOfVariationThreshold > 0.0 && coefficientOfVariationThreshold.isFinite()) {
                "coefficientOfVariationThreshold must be > 0 and finite; was $coefficientOfVariationThreshold"
            }
            require(streamNum >= 0) {
                "streamNum must be >= 0; was $streamNum"
            }
        }
    }
}

/**
 * Serializable selection of a simulation-optimization solver and its
 * algorithm-specific parameters.
 *
 * Each sealed variant captures the constructor parameters that are plain
 * data; non-serializable arguments to the live solver constructors
 * (evaluator, stream provider, problem definition, equality checkers,
 * functional sampler/elite-size hooks) are supplied by
 * `OptimizationSolverFactory` and are not part of the persisted document.
 *
 * Sealed-class polymorphic serialization is used: the JSON/TOML output
 * carries a `"type"` discriminator with values `"stochasticHillClimbing"`,
 * `"simulatedAnnealing"`, `"crossEntropy"`, or `"rSpline"`.
 *
 * Random-restart is **not** a separate sealed variant.  Any algorithm can
 * be wrapped by setting [randomRestart] on its variant; setting it to
 * `null` (the default) disables the wrapper.  This keeps the sealed
 * hierarchy focused on algorithm choice and lets every algorithm be
 * wrapped uniformly.
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
        @TomlComment(
            "Inline table or omitted. Optional starting point keyed by\n" +
            "decision-variable name (e.g. { reorderPoint = 10.0,\n" +
            "reorderQuantity = 50.0 }).  Omit to let the solver pick its\n" +
            "own starting point."
        )
        override val startingPoint: Map<String, Double>? = null,

        @TomlComment(
            "Integer. Maximum number of solver main-loop iterations.\n" +
            "Must be > 0."
        )
        override val maxIterations: Int,

        @TomlComment(
            "Table or omitted. When present, wraps the solver in a\n" +
            "random-restart wrapper that performs up to maxNumRestarts\n" +
            "restarts from independently-drawn starting points."
        )
        override val randomRestart: RandomRestartSpec? = null,

        @TomlComment(
            "Integer. Random-number stream number used to seed the\n" +
            "solver's stochastic decisions.  0 means 'next available\n" +
            "stream'.  Must be >= 0.  Default: 0."
        )
        override val streamNum: Int = 0,

        @TomlComment(
            "String or omitted. Optional human-readable solver instance\n" +
            "name used for tracker output and reports."
        )
        override val name: String? = null,

        @TomlComment(
            "Integer. Number of simulation replications requested per\n" +
            "evaluation (per oracle call).  Must be > 0."
        )
        val replicationsPerEvaluation: Int
    ) : SolverSpec() {
        init {
            require(maxIterations > 0) { "maxIterations must be > 0; was $maxIterations" }
            require(streamNum >= 0) { "streamNum must be >= 0; was $streamNum" }
            require(replicationsPerEvaluation > 0) {
                "replicationsPerEvaluation must be > 0; was $replicationsPerEvaluation"
            }
        }
    }

    /** Simulated Annealing.  Mirrors
     *  [ksl.simopt.solvers.algorithms.SimulatedAnnealing]. */
    @Serializable
    @SerialName("simulatedAnnealing")
    data class SimulatedAnnealing(
        @TomlComment(
            "Inline table or omitted. Optional starting point keyed by\n" +
            "decision-variable name.  Omit to let the solver pick its own."
        )
        override val startingPoint: Map<String, Double>? = null,

        @TomlComment(
            "Integer. Maximum number of solver main-loop iterations.\n" +
            "Must be > 0."
        )
        override val maxIterations: Int,

        @TomlComment(
            "Table or omitted. When present, wraps the solver in a\n" +
            "random-restart wrapper."
        )
        override val randomRestart: RandomRestartSpec? = null,

        @TomlComment(
            "Integer. Random-number stream number; 0 means 'next\n" +
            "available stream'.  Must be >= 0.  Default: 0."
        )
        override val streamNum: Int = 0,

        @TomlComment(
            "String or omitted. Optional human-readable solver instance\n" +
            "name."
        )
        override val name: String? = null,

        @TomlComment(
            "Integer. Number of simulation replications requested per\n" +
            "evaluation.  Must be > 0."
        )
        val replicationsPerEvaluation: Int,

        @TomlComment(
            "Table. Strategy for choosing the initial temperature.\n" +
            "type = 'fixed' (use a fixed value) or 'autoCalibrate' (run\n" +
            "a brief random walk to estimate one).  Default:\n" +
            "{ type = 'autoCalibrate', targetProbability = 0.8,\n" +
            "sampleSize = 100 }."
        )
        val temperature: TemperatureSpec = TemperatureSpec.AutoCalibrate(),

        @TomlComment(
            "Table. Cooling-schedule selection.  type = 'linear',\n" +
            "'exponential', or 'logarithmic'.  Required."
        )
        val coolingSchedule: CoolingScheduleSpec,

        @TomlComment(
            "Number. Temperature below which the algorithm halts the\n" +
            "main loop early (even if maxIterations not yet reached).\n" +
            "Must be > 0 and finite."
        )
        val stoppingTemperature: Double
    ) : SolverSpec() {
        init {
            require(maxIterations > 0) { "maxIterations must be > 0; was $maxIterations" }
            require(streamNum >= 0) { "streamNum must be >= 0; was $streamNum" }
            require(replicationsPerEvaluation > 0) {
                "replicationsPerEvaluation must be > 0; was $replicationsPerEvaluation"
            }
            require(stoppingTemperature > 0.0 && stoppingTemperature.isFinite()) {
                "stoppingTemperature must be > 0 and finite; was $stoppingTemperature"
            }
        }
    }

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
        @TomlComment(
            "Inline table or omitted. Optional starting point keyed by\n" +
            "decision-variable name.  Omit to let the solver pick its own."
        )
        override val startingPoint: Map<String, Double>? = null,

        @TomlComment(
            "Integer. Maximum number of solver main-loop iterations.\n" +
            "Must be > 0."
        )
        override val maxIterations: Int,

        @TomlComment(
            "Table or omitted. When present, wraps the solver in a\n" +
            "random-restart wrapper."
        )
        override val randomRestart: RandomRestartSpec? = null,

        @TomlComment(
            "Integer. Random-number stream number; 0 means 'next\n" +
            "available stream'.  Must be >= 0.  Default: 0."
        )
        override val streamNum: Int = 0,

        @TomlComment(
            "String or omitted. Optional human-readable solver instance\n" +
            "name."
        )
        override val name: String? = null,

        @TomlComment(
            "Integer. Number of simulation replications requested per\n" +
            "evaluation.  Must be > 0."
        )
        val replicationsPerEvaluation: Int,

        @TomlComment(
            "Table. Sampler used to parameterise the search distribution.\n" +
            "type = 'normal' (multivariate normal; currently the only\n" +
            "option).  Default: { type = 'normal', meanSmoother = 0.85,\n" +
            "sdSmoother = 0.85, coefficientOfVariationThreshold = 0.03,\n" +
            "streamNum = 0 }."
        )
        val sampler: CESamplerSpec = CESamplerSpec.Normal(),

        @TomlComment(
            "Number or omitted. Elite percentage used to update the\n" +
            "sampling distribution each iteration.  Omit to use the\n" +
            "solver's built-in default.  Must lie strictly in (0, 1)\n" +
            "when present."
        )
        val elitePct: Double? = null,

        @TomlComment(
            "Integer or omitted. Number of candidates sampled per CE\n" +
            "iteration.  Omit to use the solver's built-in default.\n" +
            "Must be >= 1 when present."
        )
        val ceSampleSize: Int? = null
    ) : SolverSpec() {
        init {
            require(maxIterations > 0) { "maxIterations must be > 0; was $maxIterations" }
            require(streamNum >= 0) { "streamNum must be >= 0; was $streamNum" }
            require(replicationsPerEvaluation > 0) {
                "replicationsPerEvaluation must be > 0; was $replicationsPerEvaluation"
            }
            require(elitePct == null || (elitePct > 0.0 && elitePct < 1.0)) {
                "elitePct must be strictly in (0, 1) when non-null; was $elitePct"
            }
            require(ceSampleSize == null || ceSampleSize >= 1) {
                "ceSampleSize must be >= 1 when non-null; was $ceSampleSize"
            }
        }
    }

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
        @TomlComment(
            "Inline table or omitted. Optional starting point keyed by\n" +
            "decision-variable name.  Omit to let the solver pick its own."
        )
        override val startingPoint: Map<String, Double>? = null,

        @TomlComment(
            "Integer. Maximum number of solver main-loop iterations.\n" +
            "Must be > 0."
        )
        override val maxIterations: Int,

        @TomlComment(
            "Table or omitted. When present, wraps the solver in a\n" +
            "random-restart wrapper."
        )
        override val randomRestart: RandomRestartSpec? = null,

        @TomlComment(
            "Integer. Random-number stream number; 0 means 'next\n" +
            "available stream'.  Must be >= 0.  Default: 0."
        )
        override val streamNum: Int = 0,

        @TomlComment(
            "String or omitted. Optional human-readable solver instance\n" +
            "name."
        )
        override val name: String? = null,

        @TomlComment(
            "Integer. Replications requested for the first evaluation in\n" +
            "the growth schedule.  Must be > 0."
        )
        val initialNumReps: Int,

        @TomlComment(
            "Number. Per-iteration growth factor applied to the\n" +
            "replication count.  Must be > 0 and finite."
        )
        val sampleSizeGrowthRate: Double,

        @TomlComment(
            "Integer. Hard cap on replications per evaluation.  Must be\n" +
            ">= initialNumReps."
        )
        val maxNumReplications: Int
    ) : SolverSpec() {
        init {
            require(maxIterations > 0) { "maxIterations must be > 0; was $maxIterations" }
            require(streamNum >= 0) { "streamNum must be >= 0; was $streamNum" }
            require(initialNumReps > 0) {
                "initialNumReps must be > 0; was $initialNumReps"
            }
            require(sampleSizeGrowthRate > 0.0 && sampleSizeGrowthRate.isFinite()) {
                "sampleSizeGrowthRate must be > 0 and finite; was $sampleSizeGrowthRate"
            }
            require(maxNumReplications >= initialNumReps) {
                "maxNumReplications ($maxNumReplications) must be >= initialNumReps ($initialNumReps)"
            }
        }
    }
}
