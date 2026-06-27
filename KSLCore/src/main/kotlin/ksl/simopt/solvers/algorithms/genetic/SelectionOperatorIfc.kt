package ksl.simopt.solvers.algorithms.genetic

import ksl.simopt.evaluator.Solution

/**
 *  Strategy interface for the selection step of a genetic algorithm. Given the current
 *  evaluated population, a selection operator chooses the parents (the mating pool) that
 *  will be recombined to produce the next generation. Operators draw any required randomness
 *  through the supplied solver's single random number stream ([GeneticAlgorithmSolver.rnStream]),
 *  keeping a run reproducible.
 */
fun interface SelectionOperatorIfc {

    /**
     *  Selects parents from the current evaluated population.
     *
     *  @param population the current generation's evaluated solutions. Must not be empty.
     *  @param numToSelect the number of parents to produce (the mating pool size). Must be > 0.
     *  @param solver the genetic algorithm solver requesting the selection. Provides access to
     *  the random number stream, the problem definition, and the solution comparison rule.
     *  @return the selected parent solutions. The returned list will have size [numToSelect].
     */
    fun select(
        population: List<Solution>,
        numToSelect: Int,
        solver: GeneticAlgorithmSolver
    ): List<Solution>
}
