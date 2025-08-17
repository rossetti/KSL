package ksl.simopt.evaluator

import ksl.simopt.evaluator.SolutionsIfc.Companion.defaultCapacity
import java.util.PriorityQueue

/**
 * Class to support a group of solutions (all containing inputs, responses, objective fns, penalties)
 * The solutions are naturally ordered by comparison of Solution instances based on
 * their penalized objective functions (without regard to sampling error).
 *
 * @param capacity the capacity for the solutions. Constrains the total number of solutions in-memory.
 * The default capacity is [defaultCapacity]. Oldest solutions are evicted first.
 *  @param allowInfeasibleSolutions if true input-infeasible solutions are allowed to be
 *  saved. If false, input-infeasible solutions are silently ignored.
 *  The default is false (do not allow input-infeasible solutions to be saved)
 */
class Solutions(
    capacity: Int = defaultCapacity,
    var allowInfeasibleSolutions: Boolean = false
) : SolutionsIfc {
    init {
        require(capacity >= 1) { "The minimum capacity is 1" }
    }

    override var capacity: Int = capacity
        private set

    /**
     * Class to support a group of solutions (all containing inputs, responses, objective fns, penalties)
     * The solutions are naturally ordered by comparison of Solution instances based on
     * their penalized objective functions (without regard to sampling error).
     *
     * @param solutions the initial list of solutions to add
     * @param capacity the capacity for the solutions. Constrains the total number of solutions in-memory.
     * The default capacity is [defaultCapacity]. Oldest solutions are evicted first.
     *  @param allowInfeasibleSolutions if true input-infeasible solutions are allowed to be
     *  saved. If false, input-infeasible solutions are silently ignored.
     *  The default is false (do not allow input-infeasible solutions to be saved)
     */
    constructor(
        solutions: List<Solution>,
        capacity: Int = defaultCapacity,
        allowInfeasibleSolutions: Boolean = false
    ) : this(capacity, allowInfeasibleSolutions) {
        addAll(solutions)
    }

    private val mySolutions = PriorityQueue<Solution>()
    private val myEnteredSolutions = ArrayDeque<Solution>()

    @Suppress("unused")
    override fun increaseCapacity(increase: Int){
        if(increase <= 0) return
        capacity = capacity + increase
    }

    /**
     *  Adds all the solutions to the sequence of solutions. If the capacity
     *  is met, then the oldest (first) item is evicted and returned. Each
     *  evicted item is returned in the order of eviction.
     *  @param solutions the solutions to add
     *  @return a list of possibly evicted items
     */
    fun addAll(solutions: List<Solution>): List<Solution> {
        val list = mutableListOf<Solution>()
        for (solution in solutions) {
            val evicted = add(solution)
            if (evicted != null) {
                list.add(evicted)
            }
        }
        return list
    }

    /**
     *  Adds the solution to the solutions.
     *
     *  If the solution is input-infeasible and the allowInfeasibleSolutions
     *  flag is false, then the solution is silently ignored.
     *
     *  If the capacity is met, then the worst solution is evicted and returned.
     *
     *  If the solution is already in the sequence of solutions (based on input-equality),
     *  and it has more samples than the existing solution, then the existing solution is replaced;
     *  otherwise the existing solution is not replaced.
     *
     *  @param solution the solution to add
     *  @return a possibly evicted item or null if the solution was not added
     */
    fun add(solution: Solution): Solution? {
        if (contains(solution)) return null
        if(!solution.isInputFeasible()){
            return null
        }
        mySolutions.firstOrNull { it.inputMap == solution.inputMap }?.let {
            // found an input map duplicate
            if (solution.count <= it.count ) {
                // found a solution with a larger count with the same inputs
                // ignore the new solution, keep the one with more samples
                return null
            } else  {
               //else incoming solution has more samples, but is a duplicate keep it
                mySolutions.remove(it)
                myEnteredSolutions.remove(it)
                myEnteredSolutions.add(solution)
                mySolutions.add(solution)
                return it
            }
        }
        if (myEnteredSolutions.size == capacity) {
            // reached capacity, need to check if new solution is better than the worst solution
            // find the worst solution
            val worst = orderedSolutions.last()
            if (solution < worst){
                myEnteredSolutions.remove(worst)
                mySolutions.remove(worst)
                myEnteredSolutions.add(solution)
                mySolutions.add(solution)
                return worst
            } else {
                return null
            }
        }
        // there is capacity for the new solution, just add it
        myEnteredSolutions.add(solution)
        mySolutions.add(solution)
        return null
    }

    /**
     *  Removes the specified element
     */
    fun remove(solution: Solution) {
        myEnteredSolutions.remove(solution)
        mySolutions.remove(solution)
    }

    /**
     *  Clears all solutions
     */
    fun clear() {
        mySolutions.clear()
        myEnteredSolutions.clear()
    }

    /**
     *  A time-ordered list of the solution, where 0 is the first (oldest)
     *  solution added, 1 is the next, etc.
     */
    @Suppress("unused")
    val enteredSolutions: List<Solution>
        get() = myEnteredSolutions

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

    /**
     *  The solution with the lowest penalized objective function value.
     *  The solution may or may not be feasible.
     */
    override fun peekBest(): Solution? {
        return mySolutions.peek()
    }

    override val size: Int
        get() = myEnteredSolutions.size

    override operator fun get(index: Int): Solution {
        return myEnteredSolutions[index]
    }

    override fun isEmpty(): Boolean {
        return myEnteredSolutions.isEmpty()
    }

    override fun iterator(): Iterator<Solution> {
        return myEnteredSolutions.iterator()
    }

    override fun listIterator(): ListIterator<Solution> {
        return myEnteredSolutions.listIterator()
    }

    override fun listIterator(index: Int): ListIterator<Solution> {
        return myEnteredSolutions.listIterator(index)
    }

    override fun subList(fromIndex: Int, toIndex: Int): List<Solution> {
        return myEnteredSolutions.subList(fromIndex, toIndex)
    }

    override fun lastIndexOf(element: Solution): Int {
        return myEnteredSolutions.lastIndexOf(element)
    }

    override fun indexOf(element: Solution): Int {
        return myEnteredSolutions.indexOf(element)
    }

    override fun containsAll(elements: Collection<Solution>): Boolean {
        return myEnteredSolutions.containsAll(elements)
    }

    override fun contains(element: Solution): Boolean {
        return myEnteredSolutions.contains(element)
    }

    //TODO need to implement screenToBest function
}