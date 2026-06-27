package ksl.simopt.solvers.algorithms.genetic

import ksl.simopt.problem.ProblemDefinition

/**
 *  Uniform-reset mutation: an alternate mutation operator. Each coordinate `i` is, with probability
 *  [perGeneRate], replaced by a value drawn uniformly from input `i`'s defined range (so it has no
 *  memory of the current value). Coordinates with a non-positive range are left unchanged. The
 *  returned point is a new array; the supplied point is not modified. The result is range-feasible
 *  by construction, but the solver still passes it through
 *  [ksl.simopt.problem.ProblemDefinition.toInputMap] for granularity rounding.
 *
 *  @param problemDefinition the problem definition providing per-input ranges
 *  @param perGeneRate the probability that an individual coordinate is reset. Must be in [0,1]. The
 *  default is [defaultPerGeneRate].
 */
class UniformResetMutation(
    val problemDefinition: ProblemDefinition,
    perGeneRate: Double = defaultPerGeneRate
) : MutationOperatorIfc {

    var perGeneRate: Double = perGeneRate
        set(value) {
            require((value >= 0.0) && (value <= 1.0)) { "The per-gene rate must be in [0,1]" }
            field = value
        }

    init {
        require((perGeneRate >= 0.0) && (perGeneRate <= 1.0)) { "The per-gene rate must be in [0,1]" }
    }

    // Per-coordinate lower bounds and ranges derived from the problem's midpoints and ranges.
    private val ranges: DoubleArray = problemDefinition.inputRanges
    private val lowerBounds: DoubleArray = run {
        val mid = problemDefinition.inputMidPoints
        DoubleArray(ranges.size) { mid[it] - ranges[it] / 2.0 }
    }

    override fun mutate(point: DoubleArray, solver: GeneticAlgorithmSolver): DoubleArray {
        require(point.size == ranges.size) {
            "The point dimension (${point.size}) must equal the problem input size (${ranges.size})"
        }
        val rnStream = solver.rnStream
        val result = point.copyOf()
        for (i in result.indices) {
            if (ranges[i] <= 0.0) continue // no range to sample from; leave the coordinate unchanged
            if (rnStream.randU01() < perGeneRate) {
                result[i] = rnStream.rUniform(lowerBounds[i], lowerBounds[i] + ranges[i])
            }
        }
        return result
    }

    override fun toString(): String = "UniformResetMutation(perGeneRate=$perGeneRate)"

    companion object {
        /**
         *  The default probability that an individual coordinate is reset. By default, this is 0.1.
         */
        @JvmStatic
        var defaultPerGeneRate: Double = 0.1
            set(value) {
                require((value >= 0.0) && (value <= 1.0)) { "The default per-gene rate must be in [0,1]" }
                field = value
            }
    }
}
