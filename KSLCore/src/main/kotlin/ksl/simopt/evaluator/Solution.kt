package ksl.simopt.evaluator

import ksl.simopt.problem.FeasibilityIfc
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import ksl.utilities.Interval
import ksl.utilities.math.KSLMath
import ksl.utilities.observers.Emitter
import ksl.utilities.random.rvariable.toDouble
import ksl.utilities.statistic.DEFAULT_CONFIDENCE_LEVEL

interface SolutionEmitterIfc {
    val emitter : Emitter<Solution>
}

@Suppress("unused")
class SolutionEmitter : SolutionEmitterIfc {
    override val emitter: Emitter<Solution> = Emitter()
}

/**
 *  A solution represents the evaluated inputs for on a problem definition.
 *  Solution also implements the [EstimatedResponseIfc] interface by delegating to the supplied
 *  estimated objective function. The [FeasibilityIfc] interface is implemented by delegating
 *  to the supplied input map.
 *
 *  @param inputMap the inputs (name,value) pairs associated with the solution
 *  @param estimatedObjFnc the estimated objective function from the simulation oracle
 *  @param responseEstimates the estimates of the responses associated with the response constraints
 *  @param evaluationNumber the iteration number of the solver request. That is, the number of times that
 *  the simulation oracle has been asked to evaluate (any) input.
 */
data class Solution(
    val inputMap: InputMap,
    val estimatedObjFnc: EstimatedResponse,
    val responseEstimates: List<EstimatedResponse>,
    val evaluationNumber: Int,
    val isValid: Boolean = true
) : Comparable<Solution>, FeasibilityIfc by inputMap, EstimatedResponseIfc by estimatedObjFnc {

    val id : Int = solutionCounter++

    init {
        require(inputMap.isNotEmpty()) { "The input map cannot be empty for a solution" }
        require(evaluationNumber >= 0) { "The evaluation number that caused this solution >= 0" }
    }

    val problemDefinition: ProblemDefinition
        get() = inputMap.problemDefinition

    val responseEstimatesMap: Map<String, EstimatedResponse>
        get() = responseEstimates.associateBy { it.name }

    /**
     *  The response estimate averages
     */
    val averages: Map<String, Double>
        get() = responseEstimates.associate { Pair(it.name, it.average) }

    /**
     *  The variance of the estimated responses
     */
    val variances: Map<String, Double>
        get() = responseEstimates.associate { Pair(it.name, it.variance) }

    /**
     *  The number of times that the response has been sampled. The sample
     *  size of the response estimates.
     */
    val counts: Map<String, Double>
        get() = responseEstimates.associate { Pair(it.name, it.count) }

    /**
     *  The standard deviations of the estimated responses
     */
    @Suppress("unused")
    val stdDeviations: Map<String, Double>
        get() = responseEstimates.associate { Pair(it.name, it.standardDeviation) }

    /**
     *  The violation amount for each response constraint
     */
    val responseViolations: Map<String, Double>
        get() = problemDefinition.responseConstraintViolations(averages)

    /**
     *  Converts the solution to an instance of a ResponseMap
     */
    fun toResponseMap(): ResponseMap {
        val responseMap = problemDefinition.emptyResponseMap()
        responseMap.add(estimatedObjFnc)
        for (estimate in responseEstimates) {
            responseMap.add(estimate)
        }
        return responseMap
    }

    /**
     *  Converts the solution to a map of name, value pairs.
     *  The returned values include the input map, the estimated objective function,
     *  and the estimated responses.
     */
    fun asMappedData() : Map<String, Double> {
        val map = mutableMapOf<String, Double>()
        map.putAll(inputMap)
        map.putAll(estimatedObjFnc.toResponseData())
        for (estimate in responseEstimates) {
            map.putAll(estimate.toResponseData())
        }
        return map
    }

    /**
     *  Allows comparison of solutions by the estimated objective function
     */
    @Suppress("unused")
    val objFuncComparator: Comparator<Solution>
        get() = compareBy<Solution> { it.estimatedObjFnc.average }

    /**
     *  Allows comparison of solutions by the estimated objective function
     */
    @Suppress("unused")
    val penalizedObjFuncComparator: Comparator<Solution>
        get() = compareBy<Solution> { it.penalizedObjFncValue }

    /**
     *  The user may supply a penalty function to use when computing
     *  the response constraint violation penalty; otherwise the default
     *  penalty function is used.
     */
    var penaltyFunction: PenaltyFunctionIfc? = null

    /**
     *  The total penalty associated with violating the response constraints
     */
    val responseConstraintViolationPenalty: Double
        get() {
            val p = responseViolations.values.sum() * penaltyFunctionValue
            return if (p.isNaN()) Double.MAX_VALUE else p
        }

    /**
     *  The current value of the penalty function
     */
    val penaltyFunctionValue: Double
        get() {
            return if (penaltyFunction != null) {
                minOf(penaltyFunction!!.penalty(evaluationNumber), Double.MAX_VALUE)
            } else {
                minOf(NaivePenaltyFunction.defaultPenaltyFunction.penalty(evaluationNumber), Double.MAX_VALUE)
            }
        }

    /**
     *  The estimated (average) value of the objective function
     */
    val estimatedObjFncValue: Double
        get() = if (estimatedObjFnc.average.isNaN()) Double.MAX_VALUE else estimatedObjFnc.average

    /**
     *  The estimated (average) value of the objective function but rounded to the problem's
     *  granularity for the objective function
     */
    val granularObjFncValue: Double
        get() {
            if (estimatedObjFnc.average.isNaN()) return Double.MAX_VALUE
            return KSLMath.gRound(estimatedObjFncValue, problemDefinition.objFnGranularity)
        }

    /**
     *  The penalized objective function.  That is, the estimated objective function plus
     *  the total penalty associated with violating the response constraints.
     */
    val penalizedObjFncValue: Double
        get() = estimatedObjFncValue + responseConstraintViolationPenalty

    /**
     *  The estimated (average) value of the objective function but rounded to the problem's
     *  granularity for the objective function
     */
    val granularPenalizedObjFncValue: Double
        get() {
            if (granularObjFncValue.isNaN() || (granularObjFncValue == Double.MAX_VALUE)) return Double.MAX_VALUE
            return KSLMath.gRound(penalizedObjFncValue, problemDefinition.objFnGranularity)
        }

    /**
     *  Tests if each response constraint is feasible.  If all tests are feasible, then the
     *  solution is considered response feasible.
     *
     *  @param overallCILevel the overall confidence across all response constraints.
     */
    fun isResponseConstraintFeasible(overallCILevel: Double = 0.99): Boolean {
        require(!(overallCILevel <= 0.0 || overallCILevel >= 1.0)) { "Confidence Level must be (0,1)" }
        val alpha = 1.0 - overallCILevel
        val responses = responseEstimatesMap
        val k = problemDefinition.responseConstraints.size
        val level = 1.0 - (alpha / k)
        for (rc in problemDefinition.responseConstraints){
            if (responses.containsKey(rc.responseName)) {
                val estimatedResponse = responses[rc.responseName]!!
                if (!rc.testFeasibility(estimatedResponse, level)){
                    return false
                }
            }
        }
        return true
    }

    /**
     *  Computes a one-sided upper confidence interval for each response constraint to test
     *  if the interval contains zero. If the upper limit of the interval is less than 0.0, then we can be confident
     *  that response constraint is feasible. The individual confidence interval upper limits
     *  are based on a one-sided confidence interval on the mean response assuming normality.
     *  The upper limit is computed as (x_bar - b + t(level, n-1)*s/sqrt(n)) assuming a less-than constraint.
     *  The individual confidence interval levels are adjusted to meet the overall level of confidence.
     *
     *  @param overallCILevel the overall confidence across all response constraints.
     */
    @Suppress("unused")
    fun responseConstraintOneSidedIntervals(overallCILevel: Double = 0.99): List<Interval> {
        require(!(overallCILevel <= 0.0 || overallCILevel >= 1.0)) { "Confidence Level must be (0,1)" }
        val intervals = mutableListOf<Interval>()
        val alpha = 1.0 - overallCILevel
        val responses = responseEstimatesMap
        val k = problemDefinition.responseConstraints.size
        val level = 1.0 - (alpha / k)
        for (rc in problemDefinition.responseConstraints) {
            if (responses.containsKey(rc.responseName)) {
                val estimatedResponse = responses[rc.responseName]!!
                intervals.add(rc.oneSidedUpperResponseInterval(estimatedResponse, level))
            }
        }
        return intervals
    }

    /**
     *  Converts the data in the solution to a list containing the data associated
     *  with the solution.
     */
    fun toSolutionData(): List<SolutionData> {
        val list = mutableListOf<SolutionData>()
        for ((inputName, value) in inputMap) {
            list.add(SolutionData(id, "input", null, inputName, value))
        }
        list.add(SolutionData(id, "solution", null, "iterationNumber", evaluationNumber.toDouble()))
        list.add(SolutionData(id, "solution", null, "isInputRangeFeasible", isInputRangeFeasible().toDouble()))
        list.add(
            SolutionData(
                id,
                "solution",
                null,
                "isLinearConstraintFeasible",
                isLinearConstraintFeasible().toDouble()
            )
        )
        list.add(
            SolutionData(
                id,
                "solution",
                null,
                "isFunctionalConstraintFeasible",
                isFunctionalConstraintFeasible().toDouble()
            )
        )
        list.add(
            SolutionData(
                id,
                "solution",
                null,
                "responseConstraintViolationPenalty",
                responseConstraintViolationPenalty
            )
        )
        list.add(SolutionData(id, "solution", null, "penalizedObjFncValue", penalizedObjFncValue))
        for ((name, value) in responseViolations) {
            list.add(SolutionData(id, "solution", "constraintViolation", name, value))
        }
        list.add(SolutionData(id, "objectiveFunction", estimatedObjFnc.name, "count", estimatedObjFnc.count))
        list.add(SolutionData(id, "objectiveFunction", estimatedObjFnc.name, "average", estimatedObjFnc.average))
        list.add(SolutionData(id, "objectiveFunction", estimatedObjFnc.name, "variance", estimatedObjFnc.variance))
        for (estimate in responseEstimates) {
            list.add(SolutionData(id, "responseEstimate", estimate.name, "count", estimate.count))
            list.add(SolutionData(id, "responseEstimate", estimate.name, "average", estimate.average))
            list.add(SolutionData(id, "responseEstimate", estimate.name, "variance", estimate.variance))
        }
        return list
    }

    override fun compareTo(other: Solution): Int {
        return penalizedObjFncValue.compareTo(other.penalizedObjFncValue)
    }

    fun asString(): String {
        return "id = $id : n = ${estimatedObjFnc.count} : objFnc = $penalizedObjFncValue : 95%ci = ${estimatedObjFnc.confidenceInterval()} : inputs : ${inputMap.inputValues.joinToString { it.toString() }} "
       // return toSolutionData().toDataFrame().toString()
    }

    override fun toString(): String {
        val sb = StringBuilder().apply{
            appendLine("Solution id = $id")
            if (!isValid){
                appendLine("The solution is invalid!")
            }
            appendLine("evaluation number = $evaluationNumber")
            appendLine("penalized objective function = $penalizedObjFncValue")
            appendLine("Inputs:")
            for((name, value) in inputMap){
                appendLine("Name = $name = $value)")
            }
            appendLine("Estimated Objective Function:")
            appendLine("name = ${estimatedObjFnc.name}")
            appendLine("average = ${estimatedObjFnc.average}")
            appendLine("variance = ${estimatedObjFnc.variance}")
            appendLine("count = ${estimatedObjFnc.count}")
        }
        return sb.toString()
    }

    companion object {
        var solutionCounter : Int = 0
            private set
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
 *  Compares solutions based on granular objective function values.
 */
@Suppress("unused")
object ObjFnGranularitySolutionComparator : Comparator<Solution> {
    override fun compare(first: Solution, second: Solution): Int {
        return first.granularObjFncValue.compareTo(second.granularObjFncValue)
    }
}

/**
 *  Compares solutions based on granular penalized objective function values.
 */
@Suppress("unused")
object PenalizedObjFnGranularitySolutionComparator : Comparator<Solution> {
    override fun compare(first: Solution, second: Solution): Int {
        return first.granularPenalizedObjFncValue.compareTo(second.granularPenalizedObjFncValue)
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
class PenalizedObjectiveFunctionConfidenceIntervalComparator(
    level: Double = DEFAULT_CONFIDENCE_LEVEL,
    indifferenceZone: Double = 0.0
) : Comparator<Solution> {
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

    override fun compare(first: Solution, second: Solution): Int {
        if (!first.isValid || !second.isValid) {
            return first.penalizedObjFncValue.compareTo(second.penalizedObjFncValue)
        }
        return EstimatedResponseIfc.compareEstimatedResponses(first, second, confidenceLevel, indifferenceZone)
    }

}