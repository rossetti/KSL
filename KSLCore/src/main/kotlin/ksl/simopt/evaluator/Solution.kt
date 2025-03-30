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

    val problemDefinition: ProblemDefinition
        get() = inputMap.problemDefinition

    init {
        require(inputMap.isNotEmpty()) { "The input map cannot be empty for a solution" }
        require(numReplications >= 1) { "The number of replications must be >= 1" }
    }

    //TODO created by Evaluator

    override fun compareTo(other: Solution): Int {
        TODO("Not yet implemented")
        //TODO perhaps just provide a Comparator based on the objective function
    }
}