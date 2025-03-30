package ksl.simopt.evaluator

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

    val responseConstraintPenalties: List<Double>
        get() = problemDefinition.responseConstraintPenalties(this)

    val averages: Map<String, Double>
        get() = map.mapValues { it.value.average }

    val variances: Map<String, Double>
        get() = map.mapValues { it.value.variance }

    val counts: Map<String, Double>
        get() = map.mapValues { it.value.count }

    val stdDeviations: Map<String, Double>
        get() = map.mapValues { it.value.standardDeviation }

    /**
     *  Converts the response map to an instance of a Solution based
     *  on the supplied evaluation request
     */
    fun toSolution(
        request: EvaluationRequest,
    ) : Solution {
        val objFnName = problemDefinition.objFnResponseName
        val estimatedObjFnc = map[objFnName]!!
        require(request.numReplications == estimatedObjFnc.count.toInt()){
            "The requested number of replications does not match the number of replications in the response."
        }
        val responseEstimates = mutableListOf<EstimatedResponse>()
        for ((name, _) in map) {
            if (name != objFnName) {
                val estimate = map[name]!!
                require(request.numReplications == estimate.count.toInt()){
                    "The requested number of replications does not match the number of replications in the response."
                }
                responseEstimates.add(estimate)
            }
        }
        val responsePenalties = responseConstraintPenalties
        val solution = Solution(
            request.inputMap,
            request.numReplications,
            estimatedObjFnc,
            responseEstimates,
            responsePenalties
        )
        return solution
    }

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

}