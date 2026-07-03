package ksl.simopt.solvers.algorithms.isc

import kotlin.math.pow

/**
 *  Recombination strategy for the ISC global phase. Produces offspring coordinate vectors from two
 *  parent coordinate vectors; the solver rounds the result to the integer grid and discards offspring
 *  that are infeasible (keeping the parents in that case).
 */
fun interface NgaCrossoverIfc {
    /** Recombines [parent1] and [parent2] into offspring coordinate vectors. */
    fun crossover(parent1: DoubleArray, parent2: DoubleArray, nga: NichingGeneticAlgorithmSolver): List<DoubleArray>
}

/**
 *  Mutation strategy for the ISC global phase. Perturbs a single coordinate vector; the solver rounds
 *  the result to the integer grid and enforces feasibility.
 */
fun interface NgaMutationIfc {
    /** Returns a mutated copy of [point]. */
    fun mutate(point: DoubleArray, nga: NichingGeneticAlgorithmSolver): DoubleArray
}

/**
 *  Arithmetical (geometric) crossover (§A.8): draws `β ~ U(0,1)` and forms the two convex
 *  combinations `β·x_i + (1−β)·x_j` and `(1−β)·x_i + β·x_j`. The solver rounds each offspring to the
 *  integer grid and keeps the parents instead of any offspring that is infeasible.
 */
class ArithmeticalCrossover : NgaCrossoverIfc {
    override fun crossover(
        parent1: DoubleArray,
        parent2: DoubleArray,
        nga: NichingGeneticAlgorithmSolver
    ): List<DoubleArray> {
        val beta = nga.rnStream.randU01()
        val child1 = DoubleArray(parent1.size) { beta * parent1[it] + (1.0 - beta) * parent2[it] }
        val child2 = DoubleArray(parent1.size) { (1.0 - beta) * parent1[it] + beta * parent2[it] }
        return listOf(child1, child2)
    }
}

/**
 *  Uniform mutation (§A.9): each coordinate is, with probability `p4 = 1/d`, reset to a value drawn
 *  uniformly over its range. Because every value remains reachable with positive probability, uniform
 *  mutation preserves the NGA's global-convergence guarantee.
 *
 *  @param mutationProbability the per-coordinate mutation probability; defaults to `1/d` when null
 */
class UniformMutation(
    var mutationProbability: Double? = null
) : NgaMutationIfc {

    override fun mutate(point: DoubleArray, nga: NichingGeneticAlgorithmSolver): DoubleArray {
        val pd = nga.problemDefinition
        val lower = pd.inputLowerBounds
        val upper = pd.inputUpperBounds
        val p4 = mutationProbability ?: (1.0 / pd.inputSize)
        val result = point.copyOf()
        for (j in result.indices) {
            if (nga.rnStream.randU01() < p4) {
                result[j] = lower[j] + nga.rnStream.randU01() * (upper[j] - lower[j])
            }
        }
        return result
    }
}

/**
 *  Non-uniform (Michalewicz) mutation (§A.9): each coordinate is, with probability [p3], perturbed by
 *  `Δ(k, y) = y · U(0,1) · max(0.005, (1 − k/K)^b)`, where `k` is the current generation, `y` is the
 *  distance to the chosen bound, and the perturbation direction (toward the upper or lower bound) is
 *  chosen with equal probability. The step shrinks as the generation count `k` approaches [bigK],
 *  focusing the search late in the run.
 *
 *  @param b the shape exponent controlling the annealing rate; must be > 0 (default 1.5)
 *  @param bigK the generation horizon `K` over which the step anneals; must be >= 1 (default 50)
 *  @param p3 the per-coordinate mutation probability; must be in [0,1] (default 0.328)
 */
class NonUniformMutation(
    var b: Double = DEFAULT_B,
    var bigK: Int = DEFAULT_K,
    var p3: Double = DEFAULT_P3
) : NgaMutationIfc {

    init {
        require(b > 0.0) { "b must be positive" }
        require(bigK >= 1) { "bigK must be >= 1" }
        require(p3 in 0.0..1.0) { "p3 must be in [0,1]" }
    }

    override fun mutate(point: DoubleArray, nga: NichingGeneticAlgorithmSolver): DoubleArray {
        val pd = nga.problemDefinition
        val lower = pd.inputLowerBounds
        val upper = pd.inputUpperBounds
        val k = nga.currentGeneration
        val anneal = max005((1.0 - k.toDouble() / bigK).pow(b))
        val result = point.copyOf()
        for (j in result.indices) {
            if (nga.rnStream.randU01() < p3) {
                val toUpper = nga.rnStream.randU01() < 0.5
                val y = if (toUpper) upper[j] - result[j] else result[j] - lower[j]
                val delta = y * nga.rnStream.randU01() * anneal
                result[j] = if (toUpper) result[j] + delta else result[j] - delta
            }
        }
        return result
    }

    private fun max005(x: Double): Double = if (x < 0.005) 0.005 else x

    companion object {
        /** Default shape exponent `b`. */
        const val DEFAULT_B: Double = 1.5

        /** Default generation horizon `K`. */
        const val DEFAULT_K: Int = 50

        /** Default per-coordinate mutation probability `p3`. */
        const val DEFAULT_P3: Double = 0.328
    }
}
