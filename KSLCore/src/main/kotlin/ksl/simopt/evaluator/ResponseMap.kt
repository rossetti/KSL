package ksl.simopt.evaluator

import ksl.simopt.problem.ProblemDefinition
import ksl.utilities.statistic.Statistic

//TODO why is this holding (String, List<Double>)
// It should just hold (String, EstimatedResponse) pairs

/**
 *  A response map holds replication data from evaluations of the simulation
 *  oracle. The key to the map is the response name which should match a named
 *  response within the simulation model and within the problem definition.
 *  The associated list of doubles is the within replication average for
 *  each replication.
 *  @param map the map containing the output values for each response
 */
class ResponseMap(
    internal val problemDefinition: ProblemDefinition,
    private val map: MutableMap<String, MutableList<Double>>
) : Map<String, List<Double>> by map {

    val statistics: Map<String, Statistic>
        get() {
            val statMap = mutableMapOf<String, Statistic>()
            for((name, data) in map) {
                statMap[name] = Statistic(name, data.toDoubleArray())
            }
            return statMap
        }

    val estimatedResponses: Map<String, EstimatedResponse>
        get() {
            val statMap = mutableMapOf<String, EstimatedResponse>()
            for((name, data) in map) {
                val stat = Statistic(name, data.toDoubleArray())
                statMap[name] = EstimatedResponse(name, stat.average, stat.variance, stat.count)
            }
            return statMap
        }

    val responseConstraintPenalties: List<Double>
        get() = problemDefinition.responseConstraintPenalties(this)

    /**
     *  Converts the response map to an instance of a Solution based
     *  on the supplied evaluation request
     */
    fun toSolution(
        request: EvaluationRequest,
    ) : Solution {
        val objFnName = problemDefinition.objFnResponseName
        val estimates = estimatedResponses
        val estimatedObjFnc = estimates[objFnName]!!
        val responseEstimates = mutableListOf<EstimatedResponse>()
        for ((name, _) in estimates) {
            if (name != objFnName) {
                responseEstimates.add(estimates[name]!!)
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
     *  Replaces the list for the specified key with the supplied list.
     *  The key must already exist in the response map.
     */
    operator fun set(key: String, list : MutableList<Double>) {
        require(map.containsKey(key)) {"The key ($key) is not in the map!"}
        map[key] = list
    }

    /**
     *  Appends the elements in the supplied list to the list associated
     *  with the supplied key within the response map. The supplied key
     *  must already exist in the response map.
     */
    fun append(key: String, list: List<Double>){
        require(map.containsKey(key)) {"The key ($key) is not in the map!"}
        map[key]!!.addAll(list)
    }

    /**
     *  Appends the data associated with the [responses] map into
     *  the response map. The supplied map of response data must include
     *  the names associated with this response map. The data from
     *  the arrays are appended to the data already within the response map.
     */
    fun appendAll(responses: Map<String, DoubleArray>){
        for((name, _) in map){
            require(responses.containsKey(name)) {"The response $name was not in the supplied map of responses"}
            append(name, responses[name]!!.asList())
        }
    }
}