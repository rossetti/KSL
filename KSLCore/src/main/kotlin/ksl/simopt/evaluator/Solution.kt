package ksl.simopt.evaluator

import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition

data class Solution(
    val inputMap: InputMap,
    val numReplications: Int,
    val estimatedObjFnc: EstimatedResponse,
    val responseEstimates: List<EstimatedResponse>,
    val responsePenalties: List<Double>
) : Comparable<Solution> {

    init {
        require(inputMap.isNotEmpty()) { "The input map cannot be empty for a solution" }
        require(numReplications >= 1) { "The number of replications must be >= 1" }
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

    val totalResponsePenalty: Double
        get() = if (responsePenalties.isNotEmpty()) responsePenalties.sum() else 0.0

    val hasResponsePenalty: Boolean
        get() = totalResponsePenalty > 0

    val penalizedObjFunc: Double
        get() = estimatedObjFnc.average + totalResponsePenalty

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

//    /**
//     *  Merges the current solution with the provided solution to
//     *  produce a new combined (merged) solution. The existing solution
//     *  and the supplied solution are not changed during the merging process.
//     */
//    fun merge(solution: Solution): Solution {
//        require(inputMap == solution.inputMap) { "The inputs must be the same in order to merge the solutions" }
//        require(responseEstimates.size == solution.responseEstimates.size) { "Cannot merge solutions with different response sizes" }
//        // We assume that the two solutions are from independent replications
//        // We now have more replications in the sample
//        val numReps = numReplications + solution.numReplications
//        // convert and merge as response maps
//        val r1 = toResponseMap()
//        val r2 = solution.toResponseMap()
//        // merge them
//        r1.mergeAll(r2)
//        // now return as merged solution
//        return r1.toSolution(inputMap, numReps)
//    }

    val objFuncComparator
        get() = compareBy<Solution> {it.estimatedObjFnc.average}

    val penalizedObjFuncComparator
        get() = compareBy<Solution> {it.penalizedObjFunc}

    override fun compareTo(other: Solution): Int {
        return penalizedObjFunc.compareTo(other.penalizedObjFunc)
    }

}