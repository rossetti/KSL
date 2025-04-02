package ksl.simopt.evaluator

import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition

/**
 *  A response map holds replication data from evaluations of the simulation
 *  oracle. The key to the map is the response name which should match a named
 *  response within the simulation model and within the problem definition.
 *  The associated list of doubles is the within replication average for
 *  each replication.
 *  @param map the map containing the output values for each response
 */
class ResponseMap(
    val problemDefinition: ProblemDefinition,
    private val map: MutableMap<String, EstimatedResponse> = mutableMapOf()
) : Map<String, EstimatedResponse> by map {

    val averages: Map<String, Double>
        get() = map.mapValues { it.value.average }

    val variances: Map<String, Double>
        get() = map.mapValues { it.value.variance }

    val counts: Map<String, Double>
        get() = map.mapValues { it.value.count }

    val stdDeviations: Map<String, Double>
        get() = map.mapValues { it.value.standardDeviation }

    /**
     *  Checks if all responses associated with the problem are currently
     *  in the map. True if all responses needed for the problem are in the map.
     *  False if at least one required response is missing from the map.
     */
    fun hasAllResponses() : Boolean {
        if (map.isEmpty()){
            return false
        }
        val responseNames = problemDefinition.allResponseNames
        for(name in responseNames){
            if (!map.containsKey(name)){
                return false
            }
        }
        return true
    }

    /**
     *  Checks if all the responses and a sufficient number of replications
     *  are available in the response map.  Once this is true,
     *  the response map can be converted into a solution.
     */
    fun hasRequestedReplications(numReplications: Int) : Boolean {
        if (!hasAllResponses()){
            return false
        }
        for(estimate in map.values){
            if (estimate.count.toInt() < numReplications) {
                return false
            }
        }
        return true
    }

//    /**
//     *  Converts the response map to an instance of a Solution based
//     *  on the supplied evaluation request. The function [hasRequestedReplications]
//     *  must return true for the requested number of replications for a solution
//     *  to be constructed.
//     *  @param inputMap the inputs to be associated with the solution
//     *  @param numReplications the number of replications
//     */
//    fun toSolution(
//        inputMap: InputMap,
//        numReplications: Int,
//    ) : Solution {
//        require(hasRequestedReplications(numReplications)){"The response map is missing requested responses for the associated problem."}
//        val objFnName = problemDefinition.objFnResponseName
//        val estimatedObjFnc = map[objFnName]!!
//        val responseEstimates = mutableListOf<EstimatedResponse>()
//        for ((name, _) in map) {
//            if (name != objFnName) {
//                val estimate = map[name]!!
//                responseEstimates.add(estimate)
//            }
//        }
//        val responsePenalties = problemDefinition.responseConstraintPenalties(this, iterationCounter)
//        val solution = Solution(
//            inputMap,
//            numReplications,
//            estimatedObjFnc,
//            responseEstimates,
//            responsePenalties
//        )
//        return solution
//    }

    /**
     *  Adds the supplied estimated response. If the estimated
     *  response is not currently in the response map, then it is added.
     *  If it is already in the response map, the old value is replaced
     *  with the supplied value.
     *  @param estimate the estimated response to add. The name
     *  of the response must be valid for the associated problem.
     */
    fun add(estimate : EstimatedResponse) {
        require(problemDefinition.isValidResponse(estimate.name))
        map[estimate.name] = estimate
    }

    /**
     *  Adds or merges the supplied estimated response
     *  @param estimate the estimated response to add. The name
     *  of the response must be valid for the associated problem.
     */
    fun merge(estimate : EstimatedResponse) {
        require(problemDefinition.isValidResponse(estimate.name))
        if (map.containsKey(estimate.name)){
            val current = map[estimate.name]!!
            map[estimate.name] = current.merge(estimate)
        } else {
            map[estimate.name] = estimate
        }
    }

    /**
     *  Adds or merges the supplied estimated responses within the response map
     *  into the response map
     *  @param responseMap the estimated responses to add. The names
     *  of the responses must be valid for the associated problem.
     */
    fun mergeAll(responseMap: ResponseMap){
        for(estimate in responseMap.values){
            merge(estimate)
        }
    }

}