package ksl.simopt.evaluator

import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition

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
) {

    init {
        require(inputMap.isNotEmpty()) { "The input map cannot be empty for a solution" }
        require(numReplications >= 1) { "The number of replications must be >= 1" }
        require(iterationNumber >= 1) { "The iteration number that caused this solution >= 1" }
    }

    val problemDefinition: ProblemDefinition
        get() = inputMap.problemDefinition

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
    val stdDeviations: Map<String, Double>
        get() = responseEstimates.associate { Pair(it.name, it.standardDeviation) }

    /**
     *  The violation amount for each response constraint
     */
    val responseViolations: List<Double>
        get() = problemDefinition.responseConstraintViolations(averages)

    /**
     *  Returns true if the solution does not violate the specified
     *  linear constraints.
     */
    fun isLinearConstraintFeasible(): Boolean {
        return problemDefinition.isLinearConstraintFeasible(inputMap)
    }

    /**
     *  Returns true if the solution does not violate the specified
     *  functional constraints.
     */
    fun isFunctionalConstraintFeasible(): Boolean {
        return problemDefinition.isFunctionalConstraintFeasible(inputMap)
    }

    /**
     *  Returns true if the solution does not violate the specified
     *  ranges for each input variable.
     */
    fun isInputRangeFeasible(): Boolean {
        return problemDefinition.isInputRangeFeasible(inputMap)
    }

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
    val objFuncComparator
        get() = compareBy<Solution> { it.estimatedObjFnc.average }


    fun computeConstraintPenalty(penaltyFunction: PenaltyFunctionIfc): Double {
        return penaltyFunction.penalty(iterationNumber)*responseViolations.sum()
    }
}

fun interface PenaltyFunctionIfc {

    fun penalty(iterationCounter: Int): Double
}