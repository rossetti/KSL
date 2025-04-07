package ksl.simopt.evaluator

import java.util.PriorityQueue

/**
 * Class to support a group of solutions (each containing inputs, responses, objective fns, penalties)
 *
 */
class Solutions {

    private val mySolutions = PriorityQueue<Solution>()

    val orderedSolutions: List<Solution>
        get() = mySolutions.toList().sorted()

    val orderedFeasibleSolutions: List<Solution>
        get() = mySolutions.toList().filter { it.isInputFeasible() }.sorted()

    fun add(solution: Solution) {
        mySolutions.add(solution)
    }

    fun removeBest(): Solution? {
        return mySolutions.poll()
    }

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
}