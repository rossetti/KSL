package ksl.simopt.solvers.algorithms.genetic

import ksl.simopt.evaluator.Solution

/**
 *  Linear-rank selection: an alternate selection operator. The population is ranked best-to-worst
 *  (using the solver's [GeneticAlgorithmSolver.compare], i.e. the minimization sense of the
 *  penalized objective). Each individual receives a selection probability that depends only on its
 *  rank, not on the magnitude of its fitness, which makes the operator robust to objective scale
 *  and sign. With [selectionPressure] `s` in `[1,2]`, the best individual (rank 0) has probability
 *  `s/n` and the worst (rank n-1) has probability `(2-s)/n`; parents are then drawn by
 *  roulette over these probabilities. `s = 1` gives uniform selection; `s = 2` gives the strongest
 *  pressure (the worst individual gets probability 0).
 *
 *  @param selectionPressure the linear-ranking selection pressure, in `[1,2]`. The default is
 *  [defaultSelectionPressure].
 */
class RankSelection(
    selectionPressure: Double = defaultSelectionPressure
) : SelectionOperatorIfc {

    var selectionPressure: Double = selectionPressure
        set(value) {
            require((value >= 1.0) && (value <= 2.0)) { "The selection pressure must be in [1,2]" }
            field = value
        }

    init {
        require((selectionPressure >= 1.0) && (selectionPressure <= 2.0)) { "The selection pressure must be in [1,2]" }
    }

    override fun select(
        population: List<Solution>,
        numToSelect: Int,
        solver: GeneticAlgorithmSolver
    ): List<Solution> {
        require(population.isNotEmpty()) { "The population must not be empty for selection" }
        require(numToSelect > 0) { "The number to select must be greater than 0" }
        val ranked = solver.orderedBestFirst(population)
        val n = ranked.size
        // Cumulative selection probabilities by rank (rank 0 == best).
        val cumulative = DoubleArray(n)
        var running = 0.0
        for (i in 0 until n) {
            val p = if (n == 1) 1.0 else (selectionPressure - 2.0 * (selectionPressure - 1.0) * i / (n - 1)) / n
            running += p
            cumulative[i] = running
        }
        val rnStream = solver.rnStream
        val selected = ArrayList<Solution>(numToSelect)
        repeat(numToSelect) {
            val u = rnStream.randU01() * running // running == total probability (≈ 1.0)
            var idx = cumulative.indexOfFirst { it >= u }
            if (idx < 0) idx = n - 1
            selected.add(ranked[idx])
        }
        return selected
    }

    override fun toString(): String = "RankSelection(selectionPressure=$selectionPressure)"

    companion object {
        /**
         *  The default linear-ranking selection pressure. By default, this is 1.5.
         */
        @JvmStatic
        var defaultSelectionPressure: Double = 1.5
            set(value) {
                require((value >= 1.0) && (value <= 2.0)) { "The default selection pressure must be in [1,2]" }
                field = value
            }
    }
}
