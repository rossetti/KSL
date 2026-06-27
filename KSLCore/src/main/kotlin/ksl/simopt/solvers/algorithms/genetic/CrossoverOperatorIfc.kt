package ksl.simopt.solvers.algorithms.genetic

/**
 *  Strategy interface for the crossover (recombination) step of a genetic algorithm. Given
 *  the input values of two parent points, a crossover operator produces one or more offspring
 *  points. The offspring are returned as raw coordinate arrays (in the problem's input order)
 *  and need not be feasible: the solver converts each offspring with
 *  [ksl.simopt.problem.ProblemDefinition.toInputMap], which clamps to the input ranges and
 *  rounds to granularity. Operators draw any required randomness through the supplied solver's
 *  single random number stream ([GeneticAlgorithmSolver.rnStream]).
 */
fun interface CrossoverOperatorIfc {

    /**
     *  Recombines two parents into one or more offspring.
     *
     *  @param parent1 the input values of the first parent (problem input order)
     *  @param parent2 the input values of the second parent (problem input order). Has the
     *  same length as [parent1].
     *  @param solver the genetic algorithm solver requesting the crossover. Provides access to
     *  the random number stream and the problem definition.
     *  @return the offspring as a list of coordinate arrays, each the same length as the parents.
     *  The returned points may be infeasible; the solver clamps and rounds them.
     */
    fun crossover(
        parent1: DoubleArray,
        parent2: DoubleArray,
        solver: GeneticAlgorithmSolver
    ): List<DoubleArray>
}
