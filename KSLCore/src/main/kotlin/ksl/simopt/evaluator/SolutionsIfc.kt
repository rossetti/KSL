package ksl.simopt.evaluator

interface SolutionsIfc : List<Solution> {

    /**
     *  A list of solutions ordered by penalized
     *  objective function. The solutions may or may not be feasible.
     */
    val orderedSolutions: List<Solution>

    /**
     *  A list of solutions that are input feasible ordered by penalized
     *  objective function.
     */
    val orderedInputFeasibleSolutions: List<Solution>

    /**
     *  A list of solutions ordered by penalized objective function that are input
     *  feasible and have tested as response constraint feasible.
     *  @param overallCILevel the overall confidence across all response constraints used in the testing.
     */
    fun orderedResponseFeasibleSolutions(overallCILevel: Double = 0.99): List<Solution> {
        return orderedInputFeasibleSolutions.filter { !it.isResponseConstraintFeasible(overallCILevel) }
    }

    /**
     *  The solution with the lowest penalized objective function value.
     *  The solution may or may not be feasible.
     */
    fun peekBest(): Solution?

}