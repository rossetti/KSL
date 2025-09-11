package ksl.simopt.evaluator

import ksl.utilities.statistic.DEFAULT_CONFIDENCE_LEVEL
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.api.toDataFrame
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator

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

    /**
     *  Returns a list of solutions that are possibly the best by using
     *  the supplied comparator.
     *  @param comparator the comparator to use to compare the solutions
     */
    fun possiblyBest(
        comparator: Comparator<Solution>
    ): Solutions {
        val ordered = orderedSolutions
        val solutions = Solutions()
        if (ordered.isEmpty()) return solutions
        // Since ordered solutions is not empty, there is at least one best solution.
        val best = ordered.first()
        for(solution in ordered){
            if(comparator.compare(solution, best) <= 0){
                solutions.add(solution)
            }
        }
        return solutions
    }

    /**
     *  Returns a list of solutions that are possibly the best by using
     *  a [PenalizedObjectiveFunctionConfidenceIntervalComparator].
     *  The basic procedure is to select the smallest or largest solution as the best
     *  dependent on the objective.  Then, this procedure uses the best solution as the standard and
     *  compares all the solutions with it in a pair-wise manner.  Any solutions that are considered
     *  not statistically different from the best solution are returned. The confidence interval is for
     *  each individual comparison with the best.  Thus, to control the overall confidence, users
     *  will want to adjust the individual confidence interval level such that the overall confidence
     *  in the process is controlled. See the theory of related to multi-comparison discussed
     *  [here](https://rossetti.github.io/KSLBook/simoacomparingSystems.html#simoacomparingSystemsMCB)
     *  The process used here is approximate.
     *
     *  @param level the level of confidence to use. By default, this is set to [DEFAULT_CONFIDENCE_LEVEL].
     *  @param indifferenceZone the indifference zone to use. By default, this is set to 0.0.
     */
    @Suppress("unused")
    fun possiblyBest(
        level: Double = DEFAULT_CONFIDENCE_LEVEL,
        indifferenceZone: Double = 0.0
    ): Solutions {
        return possiblyBest(
            PenalizedObjectiveFunctionConfidenceIntervalComparator(
                level = level,
                indifferenceZone = indifferenceZone
            )
        )
    }

    /**
     *  Returns a map of the data associated with the solutions.
     */
    fun toDataMap() : Map<String, List<Double>>{
        if (isEmpty()) return emptyMap()
        val map = mutableMapOf<String, MutableList<Double>>()
        for (solution in this) {
            val data = solution.asMappedData()
            for ((key, value) in data) {
                if (map.containsKey(key)) {
                    map[key]!!.add(value)
                } else {
                    map[key] = mutableListOf(value)
                }
            }
        }
        return map
    }

    /**
     *  Returns a DataFrame of the data associated with the solutions.
     */
    fun toDataFrame(): AnyFrame {
        return toDataMap().toDataFrame()
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
    fun add(solution: Solution): Solution?

    /**
     *  Adds all the solutions to the sequence of solutions. If the capacity
     *  is met, then the oldest (first) item is evicted and returned. Each
     *  evicted item is returned in the order of eviction.
     *  @param solutions the solutions to add
     *  @return a list of possibly evicted items
     */
    fun addAll(solutions: List<Solution>): List<Solution>

    /**
     *  Removes the specified element
     */
    fun remove(solution: Solution)

    /**
     *  Clears all solutions
     */
    fun clear()

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

/**
 *  Returns a map of the data associated with the solutions.
 */
fun List<Solution>.toDataMap() : Map<String, List<Double>>{
    if (isEmpty()) return emptyMap()
    val map = mutableMapOf<String, MutableList<Double>>()
    for (solution in this) {
        val data = solution.asMappedData()
        for ((key, value) in data) {
            if (map.containsKey(key)) {
                map[key]!!.add(value)
            } else {
                map[key] = mutableListOf(value)
            }
        }
    }
    return map
}

/**
 *  Returns a DataFrame of the data associated with the solutions.
 */
fun List<Solution>.toDataFrame(): AnyFrame {
    return toDataMap().toDataFrame()
}