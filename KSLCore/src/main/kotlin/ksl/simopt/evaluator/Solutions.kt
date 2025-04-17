package ksl.simopt.evaluator

import java.util.PriorityQueue

/**
 * Class to support a group of solutions (each containing inputs, responses, objective fns, penalties)
 * The solutions are naturally ordered by comparison of Solution instances based on
 * their penalized objective functions (without regard to sampling error).
 */
class Solutions() : SolutionsIfc {

    constructor(solutions: List<Solution>): this(){
        addAll(solutions)
    }

    private val mySolutions = PriorityQueue<Solution>()
    private val myEnteredSolutions = mutableListOf<Solution>()
    val solutionSequence: Sequence<Solution>
         get() = myEnteredSolutions.asSequence()
    
    /**
     *  A list of solutions ordered by penalized
     *  objective function. The solutions may or may not be feasible.
     */
    override val orderedSolutions: List<Solution>
        get() = mySolutions.toList().sorted()

    /**
     *  A list of solutions that are input feasible ordered by penalized
     *  objective function.
     */
    override val orderedInputFeasibleSolutions: List<Solution>
        get() = mySolutions.toList().filter { it.isInputFeasible() }.sorted()

    fun addAll(solutions: List<Solution>) {
        mySolutions.addAll(solutions.toMutableList())
        myEnteredSolutions.addAll(solutions)
    }

    fun add(solution: Solution) {
        mySolutions.add(solution)
        myEnteredSolutions.add(solution)
    }

    /**
     *  Removes and returns the solution with the lowest penalized objective function value.
     *  The solution may or may not be feasible.
     */
    fun removeBest(): Solution? {
        val best = mySolutions.poll()
        myEnteredSolutions.remove(best)
        return best
    }

    /**
     *  The solution with the lowest penalized objective function value.
     *  The solution may or may not be feasible.
     */
    override fun peekBest(): Solution? {
        return mySolutions.peek()
    }

    override val isEmpty: Boolean
        get() = mySolutions.isEmpty()

    fun clear() {
        mySolutions.clear()
        myEnteredSolutions.clear()
    }

    override val size: Int
        get() = mySolutions.size

    //TODO need to implement screenToBest function
}