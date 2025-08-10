package ksl.simopt.evaluator

import ksl.utilities.math.KSLMath
import ksl.utilities.math.KSLMath.defaultNumericalPrecision

/**
 *  A solution checker holds solutions up to a capacity (threshold). The solution checker will
 *  hold a maximum number of solutions to check (capacity/threshold).
 *
 *  @param noImproveThreshold This value is used as a termination threshold for the largest number of iterations, during which no
 *  improvement of the captured solutions is found. By default, set to 5, which can be controlled
 *  globally via the companion object's [defaultNoImproveThreshold]
 */
class SolutionChecker(
    noImproveThreshold: Int = defaultNoImproveThreshold
) {
    init {
        require(noImproveThreshold > 0) { "The no improvement threshold must be greater than 0" }
    }

    /**
     * This value is used as a termination threshold for the largest number of iterations, during which no
     * improvement of the captured solutions is found. By default, set to 5, which can be controlled
     * globally via the companion object's [defaultNoImproveThreshold]
     */
    var noImproveThreshold: Int = noImproveThreshold
        set(value) {
            require(value > 0) { "The no improvement threshold must be greater than 0" }
            field = value
        }

    private val myLastSolutions: ArrayDeque<Solution> = ArrayDeque()

    fun clear() {
        myLastSolutions.clear()
    }

    fun captureSolution(solution: Solution) {
        if (myLastSolutions.size == noImproveThreshold) {
            myLastSolutions.removeFirstOrNull()
        }
        myLastSolutions.add(solution)
    }

    @Suppress("unused")
    val lastSolutions: List<Solution>
        get() = myLastSolutions.toList()

    /**
     *  This will cause the solution checker to test whether the contained solutions
     *  are all the same based on the comparator
     *  @param solutionComparator the comparator to use for checking
     */
    fun checkSolutions(solutionComparator: Comparator<Solution>): Boolean {
        if (myLastSolutions.size < noImproveThreshold) return false
        val lastSolution = myLastSolutions.last()
        for (solution in myLastSolutions) {
            // This works but in no way accounts for variability in the comparison.
            // User can supply a SolutionQualityEvaluator
            if (solutionComparator.compare(lastSolution, solution)!= 0) {
                return false
            }
        }
        return true
    }

    companion object {

        /**
         * This value is used as the default termination threshold for the largest number of iterations, during which no
         * improvement of the best function value is found. By default, set to 5.
         */
        @JvmStatic
        var defaultNoImproveThreshold: Int = 5
            set(value) {
                require(value > 0) { "The default no improvement threshold must be greater than 0" }
                field = value
            }
    }
}

@Suppress("unused")
object penalizedObjectiveFunctionComparator : Comparator<Solution> {
    override fun compare(first: Solution, second: Solution): Int {
        return first.penalizedObjFncValue.compareTo(second.penalizedObjFncValue)
    }
}

class PenalizedObjectiveFunctionComparator(
    val solutionPrecision: Double = defaultNumericalPrecision
) : Comparator<Solution> {
    init {
        require(solutionPrecision > 0.0) {"The solution precision must be > 0.0"}
    }

    override fun compare(first: Solution, second: Solution): Int {
        val d = first.penalizedObjFncValue - second.penalizedObjFncValue
        return if (d < solutionPrecision){
            -1
        } else if (d > solutionPrecision){
            1
        } else {
            0
        }
    }
}

@Suppress("unused")
object objectiveFunctionComparator : Comparator<Solution> {
    override fun compare(first: Solution, second: Solution): Int {
        return first.estimatedObjFncValue.compareTo(second.estimatedObjFncValue)
    }
}