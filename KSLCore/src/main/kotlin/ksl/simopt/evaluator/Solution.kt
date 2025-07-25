package ksl.simopt.evaluator

import ksl.simopt.problem.FeasibilityIfc
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import ksl.utilities.Interval
import ksl.utilities.observers.Emitter
import ksl.utilities.random.rvariable.toDouble
import org.jetbrains.kotlinx.dataframe.api.toDataFrame

interface SolutionEmitterIfc {
    val emitter : Emitter<Solution>
}

class SolutionEmitter : SolutionEmitterIfc {
    override val emitter: Emitter<Solution> = Emitter()
}

/**
 *  A solution represents the evaluated inputs for on a problem definition.
 *  @param inputMap the inputs (name,value) pairs associated with the solution
 *  @param numReplications the number of replications associated with the request
 *  that caused the creation of the solution. Since a solution can have multiple
 *  requests for evaluation, this will generally be different from the sample size (count)
 *  associated with the estimate.
 *  @param estimatedObjFnc the estimated objective function from the simulation oracle
 *  @param responseEstimates the estimates of the responses associated with the response constraints
 *  @param iterationNumber the iteration number of the solver request. That is, the number of times that
 *  the simulation oracle has been asked to evaluate (any) input.
 */
data class Solution(
    val inputMap: InputMap,
    val numReplications: Int,
    val estimatedObjFnc: EstimatedResponse,
    val responseEstimates: List<EstimatedResponse>,
    val iterationNumber: Int
) : Comparable<Solution>, FeasibilityIfc by inputMap {

    val id : Int = solutionCounter++

    init {
        require(inputMap.isNotEmpty()) { "The input map cannot be empty for a solution" }
        require(numReplications >= 1) { "The number of replications must be >= 1" }
        require(iterationNumber >= 1) { "The iteration number that caused this solution >= 1" }
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
                minOf(penaltyFunction!!.penalty(iterationNumber), Double.MAX_VALUE)
            } else {
                minOf(NaivePenaltyFunction.defaultPenaltyFunction.penalty(iterationNumber), Double.MAX_VALUE)
            }
        }

    /**
     *  The estimated (average) value of the objective function
     */
    val estimatedObjFncValue: Double
        get() = if (estimatedObjFnc.average.isNaN()) Double.MAX_VALUE else estimatedObjFnc.average

    /**
     *  The penalized objective function.  That is, the estimated objective function plus
     *  the total penalty associated with violating the response constraints.
     */
    val penalizedObjFncValue: Double
        get() = estimatedObjFncValue + responseConstraintViolationPenalty

    /**
     *  Tests if each response constraint is feasible.  If all test feasible, then the
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
        list.add(SolutionData(id, "solution", null, "numReplications", numReplications.toDouble()))
        list.add(SolutionData(id, "solution", null, "iterationNumber", iterationNumber.toDouble()))
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
        return toSolutionData().toDataFrame().toString()
    }

    override fun toString(): String {
        return "id: $id : Obj. Function: $penalizedObjFncValue Inputs: $inputMap"
    }

    companion object {
        var solutionCounter : Int = 0
            private set
    }
}

