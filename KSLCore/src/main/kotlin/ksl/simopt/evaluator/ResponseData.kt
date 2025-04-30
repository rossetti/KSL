package ksl.simopt.evaluator

import kotlinx.serialization.Serializable

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
        return ResponseMap(responses.keys, responses.toMutableMap())
    }
}
