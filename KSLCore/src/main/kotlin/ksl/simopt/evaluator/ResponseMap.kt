package ksl.simopt.evaluator

import kotlinx.serialization.Serializable

/**
 *  A response map holds replication data from evaluations of the simulation
 *  oracle. The key to the map is the response name, which should match a named
 *  response within the simulation model and within the problem definition.
 *  The associated list of doubles is the within replication average for
 *  each replication.
 *  @param map the map containing the output values for each response
 */
@Serializable
data class ResponseMap(
    val modelIdentifier: String,
    val responseNames: Set<String>,
    private val map: MutableMap<String, EstimatedResponse> = mutableMapOf()
) : Map<String, EstimatedResponse> by map {

    init {
        require(modelIdentifier.isNotBlank()) { "Model identifier must not be blank" }
        require(responseNames.isNotEmpty()) { "Response names cannot be empty" }
        for(name in map.keys) {
            require(responseNames.contains(name)) {"There is no response named $name"}
        }
    }

    val averages: Map<String, Double>
        get() = map.mapValues { it.value.average }

    val variances: Map<String, Double>
        get() = map.mapValues { it.value.variance }

    val counts: Map<String, Double>
        get() = map.mapValues { it.value.count }

    @Suppress("unused")
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
    @Suppress("unused")
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

    /**
     *  Adds the supplied estimated response. If the estimated
     *  response is not currently in the response map, then it is added.
     *  If it is already in the response map, the old value is replaced
     *  with the supplied value.
     *  @param estimate the estimated response to add. The name
     *  of the response must be valid for the associated problem.
     */
    fun add(estimate : EstimatedResponse) {
        require(responseNames.contains(estimate.name)){"The estimate name ${estimate.name} is not a valid response name."}
        map[estimate.name] = estimate
    }

    /**
     *  Adds or merges the supplied estimated response
     *  @param estimate the estimated response to add. The name
     *  of the response must be valid for the associated problem.
     */
    fun merge(estimate : EstimatedResponse) {
        require(responseNames.contains(estimate.name)){"The estimate name ${estimate.name} is not a valid response name."}
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