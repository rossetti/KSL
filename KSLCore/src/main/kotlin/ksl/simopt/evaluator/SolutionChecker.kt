package ksl.simopt.evaluator

import ksl.utilities.math.KSLMath
import ksl.utilities.math.KSLMath.defaultNumericalPrecision
import ksl.utilities.statistic.DEFAULT_CONFIDENCE_LEVEL

/**
 *  A solution checker holds solutions up to a capacity (threshold). The solution checker will
 *  hold a maximum number of solutions to check (capacity/threshold).  If the contained
 *  solutions all test as equal, then the checker returns true.
 *
 *  @param equalityChecker the comparator to use for checking. The default is
 *  to use a [InputEquality]
 *  @param noImproveThreshold This value is used as a termination threshold for the largest number of iterations, during which no
 *  improvement of the captured solutions is found. By default, set to 5, which can be controlled
 *  globally via the companion object's [defaultNoImproveThreshold]
 */
class SolutionChecker(
    var equalityChecker: SolutionEqualityIfc = InputEquality,
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

    @Suppress("unused")
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
     *  are all the same based on the comparator. If any are different (not equal), then the
     *  function returns false.
     *
     */
    @Suppress("unused")
    fun checkSolutions(): Boolean {
        if (myLastSolutions.size < noImproveThreshold) return false
        val lastSolution = myLastSolutions.last()
        for (solution in myLastSolutions) {
            if (!equalityChecker.equals(lastSolution, solution)) return false
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

/**
 *  A functional interface for checking if two solutions are equal.
 */
fun interface SolutionEqualityIfc {

    fun equals(first: Solution, second: Solution): Boolean
}


/**
 *  This solution comparator returns 0 if the inputs are the same
 *  for the two solutions. If the solutions do not have the same inputs, then
 *  the penalized objective function is used to determine the ordering. Thus, two
 *  solutions are considered the same if they have the same input values, regardless of
 *  the value of the objective functions.
 */
@Suppress("unused")
object InputEquality : SolutionEqualityIfc {
    override fun equals(first: Solution, second: Solution): Boolean {
        return first.inputMap == second.inputMap
    }
}

/**
 *  A comparator for solutions based on the penalized objective function values.
 */
@Suppress("unused")
object PenalizedObjectiveFunctionComparator : Comparator<Solution> {
    override fun compare(first: Solution, second: Solution): Int {
        return first.penalizedObjFncValue.compareTo(second.penalizedObjFncValue)
    }
}


/**
 *  Equality of the solutions is based on the penalized objective function
 *  values being within a specific precision.
 *  @param solutionPrecision the precision for equality checking
 */
@Suppress("unused")
class PenalizedObjectiveFunctionEquality(
    val solutionPrecision: Double = defaultNumericalPrecision
) : SolutionEqualityIfc {
    init {
        require(solutionPrecision > 0.0) { "The solution precision must be > 0.0" }
    }

    override fun equals(first: Solution, second: Solution): Boolean {
        return KSLMath.equal(first.penalizedObjFncValue, second.penalizedObjFncValue, solutionPrecision)
    }
}


/**
 *  Checks for equality between solutions based whether the confidence interval on
 *  the difference contains the indifference zone parameter.
 *  @param level the confidence level. Must be between 0 and 1.  The default is determined
 *  by the default confidence level setting [DEFAULT_CONFIDENCE_LEVEL]
 *  @param indifferenceZone the value for which we are indifferent between the solutions. Must
 *  be greater than or equal to 0.0. The default is 0.0.
 */
@Suppress("unused")
open class ConfidenceIntervalEquality(
    level: Double = DEFAULT_CONFIDENCE_LEVEL,
    indifferenceZone: Double = 0.0
) : SolutionEqualityIfc {
    init {
        require((0.0 < level) && (level < 1.0)) { "The confidence level must be between 0 and 1" }
        require(indifferenceZone >= 0.0) { "The indifference zone parameter must be >= 0.0" }
    }

    var confidenceLevel: Double = level
        set(value) {
            require((0.0 < value) && (value < 1.0)) { "The confidence level must be between 0 and 1" }
            field = value
        }

    var indifferenceZone: Double = indifferenceZone
        set(value) {
            require(value >= 0.0) { "The indifference zone parameter must be >= 0.0" }
            field = value
        }

    override fun equals(first: Solution, second: Solution): Boolean {
        val ci = EstimatedResponseIfc.differenceConfidenceInterval(first, second, confidenceLevel)
        if (ci.upperLimit + indifferenceZone < 0.0){
            return false
        } else  if (ci.lowerLimit - indifferenceZone > 0.0){
            return false
        } else {
            return true
        }
    }

}

/**
 *  Checks for equality between solutions based whether the confidence interval on
 *  the difference contains the indifference zone parameter and whether the input
 *  variable values are the same.
 *
 *  @param level the confidence level. Must be between 0 and 1.  The default is determined
 *  by the default confidence level setting [DEFAULT_CONFIDENCE_LEVEL]
 *  @param indifferenceZone the value for which we are indifferent between the solutions. Must
 *  be greater than or equal to 0.0. The default is 0.0.
 */
@Suppress("unused")
class InputsAndConfidenceIntervalEquality(
    level: Double = DEFAULT_CONFIDENCE_LEVEL,
    indifferenceZone: Double = 0.0
) : ConfidenceIntervalEquality(level, indifferenceZone) {

    override fun equals(first: Solution, second: Solution): Boolean {
        val ci = EstimatedResponseIfc.differenceConfidenceInterval(first, second, confidenceLevel)
        return ((first.inputMap == second.inputMap) && super.equals(first, second))
    }
}
