package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.LatinHyperCubePointGenerator
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.problem.StartingPointIfc
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import ksl.simopt.solvers.Solver
import ksl.utilities.random.rng.RNStreamControlIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rng.RNStreamProviderIfc

/**
 * Represents an abstract base class for stochastic solvers.
 * This class provides foundational functionality for solvers
 * that utilize randomness during their optimization process.
 *
 * @constructor Creates a stochastic solver with the specified parameters.
 * @param problemDefinition the problem being solved
 * @param evaluator The evaluator responsible for assessing the quality of solutions. Must implement the EvaluatorIfc interface.
 * @param maximumIterations The maximum number of iterations allowed for the solving process.
 * @param replicationsPerEvaluation Strategy to determine the number of replications to perform for each evaluation.
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams; defaults to a fresh RNStreamProvider, so each solver has its own streams
 * @param name Optional name identifier for this instance of the solver.
 */
abstract class StochasticSolver(
    problemDefinition: ProblemDefinition,
    evaluator: EvaluatorIfc,
    maximumIterations: Int,
    replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    streamNum: Int = 0,
    val streamProvider: RNStreamProviderIfc = RNStreamProvider(),
    name: String? = null
) : Solver(problemDefinition, evaluator, maximumIterations, replicationsPerEvaluation, name), RNStreamControlIfc {

    /**
     * rnStream provides a reference to the underlying stream of random numbers
     */
    val rnStream: RNStreamIfc = streamProvider.rnStream(streamNum)

    val streamNumber: Int
        get() = streamProvider.streamNumber(rnStream)

    override var advanceToNextSubStreamOption: Boolean
        get() = rnStream.advanceToNextSubStreamOption
        set(value) {
            rnStream.advanceToNextSubStreamOption = value
        }

    override var resetStartStreamOption: Boolean
        get() = rnStream.resetStartStreamOption
        set(value) {
            rnStream.resetStartStreamOption = value
        }

    override fun resetStartStream() {
        rnStream.resetStartStream()
    }

    override fun resetStartSubStream() {
        rnStream.resetStartSubStream()
    }

    override fun advanceToNextSubStream() {
        rnStream.advanceToNextSubStream()
    }

    override var antithetic: Boolean
        get() = rnStream.antithetic
        set(value) {
            rnStream.antithetic = value
        }

    /**
     *  Can be supplied to provide a method for specifying a feasible starting point.
     *  The default is to randomly generate a starting point
     */
    var startingPointGenerator: StartingPointIfc? = null

    /**
     *  The default implementation will produce an input-feasible
     *  starting point by acceptance sampling of the feasible region
     *  using the problem definition.
     */
    override fun startingPoint(): InputMap {
        return startingPointGenerator?.startingPoint(problemDefinition) ?: problemDefinition.startingPoint(rnStream)
    }

    /**
     *  Generates a set of distinct input-feasible points for the problem, using one of two strategies
     *  depending on the size of the feasible grid relative to the request:
     *
     *  - **Enumeration** — when the input grid has no more distinct points than `numPoints` (and is no
     *    larger than `maxEnumeratedLatticeSize`), the exact feasible set is enumerated directly via
     *    `ProblemDefinition.enumerateFeasibleInputPoints`. This is deterministic, consumes no random
     *    draws, and returns every feasible grid point — avoiding the rejection-sampling stall that would
     *    otherwise occur when the request exceeds the number of distinct feasible points.
     *  - **Bounded rejection sampling** — otherwise, points are drawn uniformly from the feasible region
     *    and de-duplicated. The sampling is **bounded**: it gives up after
     *    `maxOf(problemDefinition.maxFeasibleSamplingIterations, 50 * numPoints)` consecutive draws yield
     *    no new point, rather than looping forever if the feasible region has fewer than `numPoints`
     *    distinct points. The `50 * numPoints` term keeps the threshold large relative to the
     *    coupon-collector expectation, so a legitimately large region is never truncated early.
     *
     *  In either case, if fewer than `numPoints` distinct feasible points exist, the smaller set is
     *  returned and a diagnostic is logged (reporting the input-lattice size and the limiting factor).
     *
     *  @param numPoints the size of the sample
     *  @return the generated feasible input points; **may contain fewer than `numPoints`** points when
     *  the feasible region has fewer than `numPoints` distinct points
     */
    @Suppress("unused")
    fun sampleInputFeasiblePoints(numPoints: Int = 1): Set<InputMap> {
        require(numPoints > 0) {"The sample size must be greater than zero!"}
        // When the feasible grid is no larger than the request (and small enough to materialize),
        // enumerate it exactly instead of rejection sampling — that is precisely the regime where
        // rejection sampling would stall on near-duplicate draws. Enumeration is deterministic and
        // consumes no random draws, so the common (large-grid) path below is left unchanged.
        val enumerated = problemDefinition.enumerateFeasibleInputPoints(minOf(numPoints.toLong(), maxEnumeratedLatticeSize))
        if (enumerated != null) {
            if (enumerated.size < numPoints) {
                warnFewerFeasiblePoints(numPoints, enumerated.size)
            }
            return enumerated.toSet()
        }
        // Otherwise rejection-sample from the (large or continuous) feasible region, bounded so it
        // cannot loop forever if the feasible region has fewer than numPoints distinct points.
        val result = mutableSetOf<InputMap>()
        val maxConsecutiveMisses = maxOf(problemDefinition.maxFeasibleSamplingIterations, 50 * numPoints)
        var consecutiveMisses = 0
        while (result.size < numPoints && consecutiveMisses < maxConsecutiveMisses) {
            if (result.add(problemDefinition.generateInputFeasibleValues(rnStream))) {
                consecutiveMisses = 0
            } else {
                consecutiveMisses++
            }
        }
        if (result.size < numPoints) {
            warnFewerFeasiblePoints(numPoints, result.size)
        }
        return result
    }

    /**
     *  Logs a diagnostic when `sampleInputFeasiblePoints` returns fewer than the requested number of
     *  distinct feasible points, reporting the actual input-lattice size and whether the lattice itself
     *  or the linear/functional constraints are the limiting factor.
     */
    private fun warnFewerFeasiblePoints(numPoints: Int, sampled: Int) {
        val lattice = problemDefinition.inputLatticeSize()
        val cause = when {
            lattice == null ->
                "the input ranges include a continuous variable, so the linear/functional " +
                    "constraints likely restrict the feasible region below $numPoints points"
            lattice < numPoints ->
                "the input lattice has only $lattice distinct point(s) from the ranges and " +
                    "granularities — fewer than the $numPoints requested"
            else ->
                "the input lattice has $lattice point(s), but the linear/functional constraints " +
                    "restrict the feasible region below $numPoints points"
        }
        Solver.logger.warn {
            "sampleInputFeasiblePoints: sampled only $sampled of $numPoints requested distinct " +
                "feasible points — $cause. Returning $sampled. Reduce the requested count " +
                "(e.g. the solver population/design size), refine an input's granularity, or widen its range."
        }
    }

    /**
     *  Generates a set of randomly generated points (inputs) for the problem. The points
     *  are sampled using Latin hyper-cube sampling over the ranges of the inputs.
     *  The points might not be feasible with respect to linear or functional constraints
     *  for the problem.
     *
     *  @param numPoints the size of the sample
     *  @return the generated inputs. The points will be feasible with respect to the problem
     */
    @Suppress("unused")
    fun sampleLatinHyperCubePoints(numPoints: Int = 1): Set<InputMap> {
        require(numPoints > 0) {"The sample size must be greater than zero!"}
        return problemDefinition.inputRangeLatinHyperCubeInputs(numPoints,rnStream).toSet()
    }

    /**
     *  Sets the starting point generator to use a randomly generated
     *  input-feasible point that is associated with the best solution found from a
     *  sampling of randomly generated points within the feasible region of the problem definition.
     *  This approach causes the simulation oracles to be run multiple times during the search.
     *  @param maxRandomStartingPoints The maximum number of random starting points to use.
     *  @param replicationsPerRandomStartingPoint The number of replications to perform for each random starting point.
     */
    @Suppress("unused")
    fun useRandomlyBestStartingPoint(
        maxRandomStartingPoints: Int = defaultMaxRandomStartingPoints,
        replicationsPerRandomStartingPoint: Int = defaultReplicationsPerRandomStartingPoint
    ) {
        startingPointGenerator = RandomlyBestStartingPoint(maxRandomStartingPoints,
            replicationsPerRandomStartingPoint)
    }

    /**
     *  Sets the starting point generator to use a randomly generated
     *  input-feasible points that are based on Latin hyper-cube sampling.
     *
     *  @param pointsPerDimension specifies the resolution for the Latin hyper-cube sampling
     */
    @Suppress("unused")
    fun useLatinHyperCubeStartingPoints(pointsPerDimension: Int) {
        startingPointGenerator = LatinHyperCubePointGenerator(pointsPerDimension, problemDefinition, streamNumber, streamProvider)
    }

    /**
     *  The default implementation will produce an input-range feasible
     *  point. The point might not be feasible with respect to deterministic
     *  constraints. By default, the next point is generated using the
     *  [generateNeighbor()] function
     */
    override fun nextPoint(): InputMap {
        return generateNeighbor(currentPoint, rnStream)
    }

    /**
     *  Represents a starting point generator that uses a randomly generated
     *  feasible point that is based on a sampling of randomly generated points
     *  within the feasible region of the problem definition.
     */
    inner class RandomlyBestStartingPoint(
        maxRandomStartingPoints: Int = defaultMaxRandomStartingPoints,
        replicationsPerRandomStartingPoint: Int = defaultReplicationsPerRandomStartingPoint
        ) : StartingPointIfc {

        val shc = StochasticHillClimber(
            problemDefinition = problemDefinition,
            evaluator = evaluator,
            maximumIterations = maxRandomStartingPoints,
            replicationsPerEvaluation = replicationsPerRandomStartingPoint,
            // Take a distinct stream from the parent's provider (not the parent's own stream)
            // so the inner search does not collide with the parent solver's stream.
            streamNum = 0,
            streamProvider = streamProvider,
            name = "Randomly Generated Best Solution"
        )

        override fun startingPoint(problemDefinition: ProblemDefinition): InputMap {
            shc.runAllIterations()
            val bestSolution = shc.bestSolution
            numOracleCalls = numOracleCalls + shc.numOracleCalls
            numReplicationsRequested = numReplicationsRequested + shc.numReplicationsRequested
            return bestSolution.inputMap
        }

    }

    override fun toString(): String {
        return """
        StochasticSolver(
            streamNumber = $streamNumber,
            streamProvider = ${streamProvider::class.simpleName},
            startingPointGenerator = ${startingPointGenerator?.let { it::class.simpleName } ?: "Default"},
            base = ${super.toString().prependIndent("    ").trimStart()}
        )
    """.trimIndent()
    }

    override val configurationProperties: Map<String, String>
        get() = super.configurationProperties + linkedMapOf(
            "streamNumber" to streamNumber.toString(),
            "streamProvider" to (streamProvider::class.simpleName ?: ""),
            "startingPointGenerator" to (startingPointGenerator?.let { it::class.simpleName } ?: "Default")
        )

    companion object {
        /**
         * The largest input-grid (lattice) size that `sampleInputFeasiblePoints` will enumerate exactly
         * rather than rejection-sample. When the number of distinct feasible grid points is no larger
         * than the request and no larger than this cap, the feasible set is enumerated directly (exact,
         * deterministic, no wasted draws); otherwise rejection sampling is used. The cap guards against
         * materializing an enormous grid if a caller requests an absurd number of points; realistic
         * population and design sizes stay far below it.
         */
        @JvmStatic
        var maxEnumeratedLatticeSize: Long = 100_000L
            set(value) {
                require(value > 0) { "The maximum enumerated lattice size must be positive." }
                field = value
            }

        /**
         * Represents the default maximum number of iterations to be executed
         * in a given process or algorithm. This value acts as a safeguard
         * to prevent indefinite looping or excessive computation.
         *
         * The default value is set to 1000, but it can be modified based
         * on specific requirements or constraints.
         */
        @JvmStatic
        var defaultMaxRandomStartingPoints = 10
            set(value) {
                require(value > 0) { "The default maximum number of iterations must be a positive value." }
                field = value
            }

        /**
         * Represents the default number of replications to be performed during an evaluation.
         *
         * This parameter defines the number of times a specific evaluation process should be repeated
         * to ensure consistency and reliability of the results. The value must always be a positive
         * integer greater than zero.
         *
         * A change to this value will affect all subsequent evaluations relying on
         * the default replication count.
         *
         * @throws IllegalArgumentException if the value set is not greater than zero.
         */
        @JvmStatic
        @Suppress("unused")
        var defaultReplicationsPerRandomStartingPoint = 5
            set(value) {
                require(value > 0) { "The default replications per evaluation must be a positive value." }
                field = value
            }

    }

}