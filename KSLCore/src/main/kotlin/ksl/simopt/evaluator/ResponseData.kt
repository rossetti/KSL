package ksl.simopt.evaluator

import kotlinx.serialization.Serializable

//TODO Why both ResponseMap and ResponseData?

@Serializable
data class ResponseData(
    val modelIdentifier: String,
    val responses: Map<String, EstimatedResponse>
) {
    init {
        require(modelIdentifier.isNotBlank()) { "Model identifier must not be blank" }
        require(responses.isNotEmpty()) { "Responses must not be empty" }
    }

    fun toResponseMap(): ResponseMap {
        return ResponseMap(modelIdentifier, responses.keys, responses.toMutableMap())
    }
}
