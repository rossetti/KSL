package ksl.simopt.evaluator

import ksl.simopt.problem.InputMap

data class Solution(
    val inputMap: InputMap,
    val numReplications: Int,
    val estimatedObjFnc: EstimatedResponse,
    val responseEstimates: List<EstimatedResponse>,
    val responsePenalties: List<Double>
) : Comparable<Solution> {

    //TODO created by Evaluator

    override fun compareTo(other: Solution): Int {
        TODO("Not yet implemented")
        //TODO perhaps just provide a Comparator based on the objective function
    }
}