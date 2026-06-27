package ksl.simopt.solvers.algorithms.genetic

/**
 *  Uniform crossover: an alternate crossover operator. For each coordinate independently, the two
 *  parents' values are swapped with probability [swapProbability]; otherwise they are inherited as
 *  is. Two complementary offspring are produced. The returned points are not clamped or rounded;
 *  the solver does that via [ksl.simopt.problem.ProblemDefinition.toInputMap].
 *
 *  @param swapProbability the per-coordinate probability of swapping the parents' values. Must be in
 *  [0,1]. The default is [defaultSwapProbability].
 */
class UniformCrossover(
    swapProbability: Double = defaultSwapProbability
) : CrossoverOperatorIfc {

    var swapProbability: Double = swapProbability
        set(value) {
            require((value >= 0.0) && (value <= 1.0)) { "The swap probability must be in [0,1]" }
            field = value
        }

    init {
        require((swapProbability >= 0.0) && (swapProbability <= 1.0)) { "The swap probability must be in [0,1]" }
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
            if (rnStream.randU01() < swapProbability) {
                child1[i] = parent2[i]
                child2[i] = parent1[i]
            } else {
                child1[i] = parent1[i]
                child2[i] = parent2[i]
            }
        }
        return listOf(child1, child2)
    }

    override fun toString(): String = "UniformCrossover(swapProbability=$swapProbability)"

    companion object {
        /**
         *  The default per-coordinate swap probability. By default, this is 0.5.
         */
        @JvmStatic
        var defaultSwapProbability: Double = 0.5
            set(value) {
                require((value >= 0.0) && (value <= 1.0)) { "The default swap probability must be in [0,1]" }
                field = value
            }
    }
}
