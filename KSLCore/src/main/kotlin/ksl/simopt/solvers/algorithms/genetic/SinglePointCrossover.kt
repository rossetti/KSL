package ksl.simopt.solvers.algorithms.genetic

/**
 *  Single-point crossover: an alternate crossover operator. A cut point `k` is chosen uniformly in
 *  `[1, d-1]` (where `d` is the dimension). The first offspring takes coordinates `0..k-1` from
 *  parent 1 and `k..d-1` from parent 2; the second offspring is the complement. Two offspring are
 *  produced. When `d == 1` there is no interior cut point, so the parents are copied unchanged. The
 *  returned points are not clamped or rounded; the solver does that via
 *  [ksl.simopt.problem.ProblemDefinition.toInputMap].
 */
class SinglePointCrossover : CrossoverOperatorIfc {

    override fun crossover(
        parent1: DoubleArray,
        parent2: DoubleArray,
        solver: GeneticAlgorithmSolver
    ): List<DoubleArray> {
        require(parent1.size == parent2.size) { "The parents must have the same dimension" }
        val d = parent1.size
        if (d == 1) {
            // No interior cut point; recombination cannot occur.
            return listOf(parent1.copyOf(), parent2.copyOf())
        }
        val k = solver.rnStream.randInt(1, d - 1)
        val child1 = DoubleArray(d)
        val child2 = DoubleArray(d)
        for (i in 0 until d) {
            if (i < k) {
                child1[i] = parent1[i]
                child2[i] = parent2[i]
            } else {
                child1[i] = parent2[i]
                child2[i] = parent1[i]
            }
        }
        return listOf(child1, child2)
    }

    override fun toString(): String = "SinglePointCrossover()"
}
