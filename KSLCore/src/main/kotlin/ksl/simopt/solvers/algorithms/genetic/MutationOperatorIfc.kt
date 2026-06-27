package ksl.simopt.solvers.algorithms.genetic

/**
 *  Strategy interface for the mutation step of a genetic algorithm. Given a single offspring
 *  point (its coordinate array in the problem's input order), a mutation operator returns a
 *  (possibly) perturbed point. The returned point may be infeasible: the solver converts it
 *  with [ksl.simopt.problem.ProblemDefinition.toInputMap], which clamps to the input ranges
 *  and rounds to granularity. Operators must not mutate the supplied array in place; they
 *  return a new array. Randomness is drawn through the supplied solver's single random number
 *  stream ([GeneticAlgorithmSolver.rnStream]).
 */
fun interface MutationOperatorIfc {

    /**
     *  Mutates a single offspring point.
     *
     *  @param point the offspring point (problem input order). Must not be modified in place.
     *  @param solver the genetic algorithm solver requesting the mutation. Provides access to
     *  the random number stream and the problem definition.
     *  @return a new, possibly perturbed point of the same length as [point]. The solver will
     *  clamp and round it.
     */
    fun mutate(point: DoubleArray, solver: GeneticAlgorithmSolver): DoubleArray
}
