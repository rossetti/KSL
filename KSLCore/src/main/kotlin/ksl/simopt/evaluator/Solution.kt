package ksl.simopt.evaluator

import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition

data class Solution(
    val inputMap: InputMap,
    val numReplications: Int,
    val estimatedObjFnc: EstimatedResponse,
    val responseEstimates: List<EstimatedResponse>,
    val responseViolations: List<Double>,
    val iterationNumber: Int
) : Comparable<Solution> {

    init {
        require(inputMap.isNotEmpty()) { "The input map cannot be empty for a solution" }
        require(numReplications >= 1) { "The number of replications must be >= 1" }
        require(iterationNumber >= 1) { "The iteration number that caused this solution >= 1" }
    }

    val problemDefinition: ProblemDefinition
        get() = inputMap.problemDefinition

    val averages: Map<String, Double>
        get() = responseEstimates.associate { Pair(it.name, it.average) }

    val variances: Map<String, Double>
        get() = responseEstimates.associate { Pair(it.name, it.variance) }

    val counts: Map<String, Double>
        get() = responseEstimates.associate { Pair(it.name, it.count) }

    val stdDeviations: Map<String, Double>
        get() = responseEstimates.associate { Pair(it.name, it.standardDeviation) }

    //TODO are these necessary/useful?

    val totalResponseViolation: Double
        get() = if (responseViolations.isNotEmpty()) responseViolations.sum() else 0.0

    val hasResponseViolations: Boolean
        get() = totalResponseViolation > 0

    val penalizedObjFunc: Double
        get() = estimatedObjFnc.average + totalResponseViolation

    /**
     *  Converts the solution to an instance of a ResponseMap
     */
    fun toResponseMap(): ResponseMap {
        val responseMap = problemDefinition.emptyResponseMap()
        responseMap.add(estimatedObjFnc)
        for(estimate in responseEstimates){
            responseMap.add(estimate)
        }
        return responseMap
    }

    val objFuncComparator
        get() = compareBy<Solution> {it.estimatedObjFnc.average}

    val penalizedObjFuncComparator
        get() = compareBy<Solution> {it.penalizedObjFunc}

    override fun compareTo(other: Solution): Int {
        return penalizedObjFunc.compareTo(other.penalizedObjFunc)
    }

}