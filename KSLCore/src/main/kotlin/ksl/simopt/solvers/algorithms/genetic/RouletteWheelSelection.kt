package ksl.simopt.solvers.algorithms.genetic

import ksl.simopt.evaluator.Solution

/**
 *  Fitness-proportional ("roulette wheel") selection: an alternate selection operator. Because the
 *  framework minimizes the (penalized) objective and that value may be negative, raw
 *  fitness-proportional weights are not meaningful. This operator therefore uses a windowed weight:
 *  for each individual, `weight = worst - f`, where `f` is the penalized objective and `worst` is
 *  the largest (worst) penalized objective in the population. The best individual receives the
 *  largest weight and the worst receives zero. When all individuals are equal (total weight 0),
 *  selection is uniform. Parents are drawn by roulette over the weights.
 */
class RouletteWheelSelection : SelectionOperatorIfc {

    override fun select(
        population: List<Solution>,
        numToSelect: Int,
        solver: GeneticAlgorithmSolver
    ): List<Solution> {
        require(population.isNotEmpty()) { "The population must not be empty for selection" }
        require(numToSelect > 0) { "The number to select must be greater than 0" }
        val rnStream = solver.rnStream
        val n = population.size
        val fitness = DoubleArray(n) { population[it].penalizedObjFncValue }
        val worst = fitness.max()
        // Windowed weights: better (smaller) objective -> larger weight; worst -> 0.
        val cumulative = DoubleArray(n)
        var total = 0.0
        for (i in 0 until n) {
            total += (worst - fitness[i])
            cumulative[i] = total
        }
        val selected = ArrayList<Solution>(numToSelect)
        if (total <= 0.0) {
            // All equal: fall back to uniform selection.
            val lastIndex = n - 1
            repeat(numToSelect) { selected.add(population[rnStream.randInt(0, lastIndex)]) }
            return selected
        }
        repeat(numToSelect) {
            val u = rnStream.randU01() * total
            var idx = cumulative.indexOfFirst { it >= u }
            if (idx < 0) idx = n - 1
            selected.add(population[idx])
        }
        return selected
    }

    override fun toString(): String = "RouletteWheelSelection()"
}
