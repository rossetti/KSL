package ksl.simopt.evaluator

import ksl.utilities.statistic.DEFAULT_CONFIDENCE_LEVEL
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.api.toDataFrame

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