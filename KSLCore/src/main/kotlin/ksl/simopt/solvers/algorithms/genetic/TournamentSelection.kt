package ksl.simopt.solvers.algorithms.genetic

import ksl.simopt.evaluator.Solution

/**
 *  Tournament selection: the default selection operator. To select each parent, [tournamentSize]
 *  competitors are drawn uniformly at random (with replacement) from the population, and the best
 *  competitor (according to the solver's [GeneticAlgorithmSolver.compare], i.e. the minimization
 *  sense of the penalized objective) wins. Larger tournaments increase selection pressure.
 *
 *  @param tournamentSize the number of competitors per tournament. Must be greater than 0. The
 *  default is [defaultTournamentSize].
 */
class TournamentSelection(
    tournamentSize: Int = defaultTournamentSize
) : SelectionOperatorIfc {

    var tournamentSize: Int = tournamentSize
        set(value) {
            require(value > 0) { "The tournament size must be greater than 0" }
            field = value
        }

    init {
        require(tournamentSize > 0) { "The tournament size must be greater than 0" }
    }

    override fun select(
        population: List<Solution>,
        numToSelect: Int,
        solver: GeneticAlgorithmSolver
    ): List<Solution> {
        require(population.isNotEmpty()) { "The population must not be empty for selection" }
        require(numToSelect > 0) { "The number to select must be greater than 0" }
        val rnStream = solver.rnStream
        val lastIndex = population.size - 1
        val selected = ArrayList<Solution>(numToSelect)
        repeat(numToSelect) {
            var best = population[rnStream.randInt(0, lastIndex)]
            repeat(tournamentSize - 1) {
                val challenger = population[rnStream.randInt(0, lastIndex)]
                if (solver.compare(challenger, best) < 0) {
                    best = challenger
                }
            }
            selected.add(best)
        }
        return selected
    }

    override fun toString(): String = "TournamentSelection(tournamentSize=$tournamentSize)"

    companion object {
        /**
         *  The default number of competitors per tournament. By default, this is 3.
         */
        @JvmStatic
        var defaultTournamentSize: Int = 3
            set(value) {
                require(value > 0) { "The default tournament size must be greater than 0" }
                field = value
            }
    }
}
