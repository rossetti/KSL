package ksl.simopt.solvers.algorithms.genetic

import kotlin.math.max
import kotlin.math.min

/**
 *  Blend crossover (BLX-alpha): the default real-coded crossover operator. For each coordinate
 *  `i`, let `cMin = min(p1[i], p2[i])` and `cMax = max(p1[i], p2[i])` with spread `I = cMax - cMin`.
 *  Each offspring's coordinate is drawn uniformly from the interval
 *  `[cMin - alpha*I, cMax + alpha*I]`, allowing exploration slightly beyond the parents' range.
 *  Two offspring are produced per call. The returned points are not clamped or rounded; the
 *  solver does that via [ksl.simopt.problem.ProblemDefinition.toInputMap].
 *
 *  When the parents share a coordinate value (`I == 0`), that coordinate is copied unchanged
 *  (the blend interval degenerates to a point).
 *
 *  @param alpha the blend expansion factor. Must be greater than 0. The default is [defaultAlpha].
 */
class BlendCrossover(
    alpha: Double = defaultAlpha
) : CrossoverOperatorIfc {

    var alpha: Double = alpha
        set(value) {
            require(value > 0.0) { "The blend alpha must be greater than 0" }
            field = value
        }

    init {
        require(alpha > 0.0) { "The blend alpha must be greater than 0" }
    }

    override fun crossover(
        parent1: DoubleArray,
        parent2: DoubleArray,
        solver: GeneticAlgorithmSolver
    ): List<DoubleArray> {
        require(parent1.size == parent2.size) { "The parents must have the same dimension" }
        val rnStream = solver.rnStream
        val d = parent1.size
        val child1 = DoubleArray(d)
        val child2 = DoubleArray(d)
        for (i in 0 until d) {
            val cMin = min(parent1[i], parent2[i])
            val cMax = max(parent1[i], parent2[i])
            val spread = cMax - cMin
            if (spread <= 0.0) {
                // Degenerate interval: parents agree on this coordinate, copy it unchanged.
                child1[i] = cMin
                child2[i] = cMin
            } else {
                val low = cMin - alpha * spread
                val high = cMax + alpha * spread
                child1[i] = rnStream.rUniform(low, high)
                child2[i] = rnStream.rUniform(low, high)
            }
        }
        return listOf(child1, child2)
    }

    override fun toString(): String = "BlendCrossover(alpha=$alpha)"

    companion object {
        /**
         *  The default blend expansion factor. By default, this is 0.5.
         */
        @JvmStatic
        var defaultAlpha: Double = 0.5
            set(value) {
                require(value > 0.0) { "The default blend alpha must be greater than 0" }
                field = value
            }
    }
}
