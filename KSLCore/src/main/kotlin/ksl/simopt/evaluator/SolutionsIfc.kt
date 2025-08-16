package ksl.simopt.evaluator

interface SolutionsIfc : List<Solution> {

    /**
     *  The capacity of the sequence of solutions.
     */
    val capacity: Int

    /**
     *  Increases the capacity of the sequence of solutions.
     *  The default increase is to increase by [defaultCapacity].
     *
     *  @param increase the amount of the increase. If 0 or negative, no increase occurs.
     *  The current capacity is increased by [increase]
     */
    fun increaseCapacity(increase: Int = defaultCapacity)

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

    companion object {
        /**
         *  The default capacity for solutions. By default, 10.
         */
        var defaultCapacity : Int = 10
            set(value) {
                require(value >= 1) { "The minimum capacity is 1" }
                field = value
            }
    }
}