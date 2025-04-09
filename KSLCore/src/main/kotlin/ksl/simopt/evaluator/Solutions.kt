package ksl.simopt.evaluator

import java.util.PriorityQueue

/**
 * Class to support a group of solutions (each containing inputs, responses, objective fns, penalties)
 *
 */
class Solutions() {

    constructor(solutions: List<Solution>): this(){
        addAll(solutions)
    }

    private val mySolutions = PriorityQueue<Solution>()

    /**
     *  A list of solutions ordered by penalized
     *  objective function. The solutions may or may not be feasible.
     */
    val orderedSolutions: List<Solution>
        get() = mySolutions.toList().sorted()

    /**
     *  A list of solutions that are input feasible ordered by penalized
     *  objective function.
     */
    val orderedFeasibleSolutions: List<Solution>
        get() = mySolutions.toList().filter { it.isInputFeasible() }.sorted()

    /**
     *  A list of solutions ordered by penalized objective function that are input
     *  feasible and have tested as response constraint feasible.
     *  @param overallCILevel the overall confidence across all response constraints used in the testing.
     */
    fun orderedResponseFeasibleSolutions(overallCILevel: Double = 0.99): List<Solution> {
        return orderedFeasibleSolutions.filter { !it.isResponseConstraintFeasible(overallCILevel) }
    }

    fun addAll(solutions: List<Solution>) {
        mySolutions.addAll(solutions.toMutableList())
    }

    fun add(solution: Solution) {
        mySolutions.add(solution)
    }

    /**
     *  Removes and returns the solution with the lowest penalized objective function value.
     *  The solution may or may not be feasible.
     */
    fun removeBest(): Solution? {
        return mySolutions.poll()
    }

    /**
     *  The solution with the lowest penalized objective function value.
     *  The solution may or may not be feasible.
     */
    fun peekBest(): Solution? {
        return mySolutions.peek()
    }

    val isEmpty: Boolean
        get() = mySolutions.isEmpty()

    fun clear() {
        mySolutions.clear()
    }

    fun size(): Int {
        return mySolutions.size
    }

    //TODO need to implement screenToBest function
}