package ksl.simopt.evaluator

import kotlinx.serialization.Serializable


/**
 *  The data associated with a request for a simulation evaluation.
 *  @param modelIdentifier the model identifier associated with the simulation model that will be executed
 *  @param numReplications the number of replications to run the model. Must be greater than 0.
 *  @param inputs The input variable and its value for parameterizing the run of the simulation
 *  @param responseNames the names of the response variables requested from the simulation results.
 *  If no response names are provided, then all responses from the simulation will be returned.
 */
@Serializable
data class RequestData(
    val modelIdentifier: String,
    val numReplications: Int,
    val inputs: Map<String, Double>,
    val responseNames: Set<String> = emptySet()
) {
    init {
        require(modelIdentifier.isNotBlank()) { "Model identifier must not be blank" }
        require(numReplications > 0) { "Number of reps must be greater than zero" }
        for(name in responseNames) {
            require(name.isNotBlank()) { "Response names must not be blank" }
        }
        for(name in inputs.keys) {
            require(name.isNotBlank()) { "Input variable names must not be blank" }
        }
    }
}
