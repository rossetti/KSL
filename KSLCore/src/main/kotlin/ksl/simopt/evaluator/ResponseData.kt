package ksl.simopt.evaluator

import kotlinx.serialization.Serializable

@Serializable
data class ResponseData(
    val modelIdentifier: String,
    val responses: Map<String, EstimatedResponse>
) {
    init {
        require(modelIdentifier.isNotBlank()) { "Model identifier must not be blank" }
    }
}
