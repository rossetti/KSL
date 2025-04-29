package ksl.simopt.evaluator

import kotlinx.serialization.Serializable

@Serializable
data class InputData(
    val modelIdentifier: String,
    val numReps: Int,
    val inputs: Map<String, Double>
) {
    init {
        require(modelIdentifier.isNotBlank()) { "Model identifier must not be blank" }
        require(numReps > 0) { "Number of reps must be greater than zero" }
    }
}
