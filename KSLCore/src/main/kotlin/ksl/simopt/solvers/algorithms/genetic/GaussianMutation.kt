package ksl.simopt.solvers.algorithms.genetic

import ksl.simopt.problem.ProblemDefinition

/**
 *  Gaussian mutation: the default real-coded mutation operator. Each coordinate `i` is, with
 *  probability [perGeneRate], perturbed by adding a zero-mean normal increment whose standard
 *  deviation is `sigmaFactor * range[i]`, where `range[i]` is the width of input `i`'s defined
 *  range. Coordinates with a non-positive range (no room to move) are left unchanged. The
 *  returned point is a new array; the supplied point is not modified. The result is not clamped
 *  or rounded; the solver does that via [ksl.simopt.problem.ProblemDefinition.toInputMap].
 *
 *  This operator scales the perturbation per coordinate using the problem's input ranges, so it
 *  needs a [ProblemDefinition] at construction. The per-coordinate scaling is what makes the
 *  operator self-sufficient and independently testable.
 *
 *  @param problemDefinition the problem definition providing per-input ranges
 *  @param perGeneRate the probability that an individual coordinate is mutated. Must be in [0,1].
 *  The default is [defaultPerGeneRate].
 *  @param sigmaFactor the fraction of an input's range used as the perturbation standard
 *  deviation. Must be greater than 0. The default is [defaultSigmaFactor].
 */
class GaussianMutation(
    val problemDefinition: ProblemDefinition,
    perGeneRate: Double = defaultPerGeneRate,
    sigmaFactor: Double = defaultSigmaFactor
) : MutationOperatorIfc {

    var perGeneRate: Double = perGeneRate
        set(value) {
            require((value >= 0.0) && (value <= 1.0)) { "The per-gene rate must be in [0,1]" }
            field = value
        }

    var sigmaFactor: Double = sigmaFactor
        set(value) {
            require(value > 0.0) { "The sigma factor must be greater than 0" }
            field = value
        }

    init {
        require((perGeneRate >= 0.0) && (perGeneRate <= 1.0)) { "The per-gene rate must be in [0,1]" }
        require(sigmaFactor > 0.0) { "The sigma factor must be greater than 0" }
    }

    private val ranges: DoubleArray = problemDefinition.inputRanges

    override fun mutate(point: DoubleArray, solver: GeneticAlgorithmSolver): DoubleArray {
        require(point.size == ranges.size) {
            "The point dimension (${point.size}) must equal the problem input size (${ranges.size})"
        }
        val rnStream = solver.rnStream
        val result = point.copyOf()
        for (i in result.indices) {
            val sigma = sigmaFactor * ranges[i]
            if (sigma <= 0.0) continue // no range to move along; leave the coordinate unchanged
            if (rnStream.randU01() < perGeneRate) {
                result[i] = result[i] + rnStream.rNormal(0.0, sigma * sigma)
            }
        }
        return result
    }

    override fun toString(): String = "GaussianMutation(perGeneRate=$perGeneRate, sigmaFactor=$sigmaFactor)"

    companion object {
        /**
         *  The default probability that an individual coordinate is mutated. By default, this is 0.1.
         */
        @JvmStatic
        var defaultPerGeneRate: Double = 0.1
            set(value) {
                require((value >= 0.0) && (value <= 1.0)) { "The default per-gene rate must be in [0,1]" }
                field = value
            }

        /**
         *  The default fraction of an input's range used as the perturbation standard deviation.
         *  By default, this is 0.1.
         */
        @JvmStatic
        var defaultSigmaFactor: Double = 0.1
            set(value) {
                require(value > 0.0) { "The default sigma factor must be greater than 0" }
                field = value
            }
    }
}
