package ksl.simopt.evaluator

import kotlinx.serialization.Serializable

/**
 *  An evaluation request is used to request simulation oracle execution on a model.
 *
 *  @param modelIdentifier the identifier for the model that will execute the requests
 *  @param modelInputs the request (input/output) for the model to use. There must not
 *  be duplicates in this list as defined by the equality of model inputs.
 *  @param crnOption indicates if the requests should be executed by the model using
 *  common random numbers. The default is false.
 */
@Serializable
data class EvaluationRequest(
    val modelIdentifier: String,
    val modelInputs: List<ModelInputs>,
    val crnOption: Boolean = false,
    val cachingAllowed: Boolean = true
) {
    init {
        require(modelInputs.isNotEmpty()) { "At least one model input must be defined!" }
        if (modelInputs.size == 1) {
            require(crnOption == false) { "The crnOption must be false when there is only one request!" }
        }
        if (crnOption) {
            require(!cachingAllowed) { "Caching is not permitted when the CRN option is true!" }
        }
        for (request in modelInputs) {
            require(request.modelIdentifier == modelIdentifier) { "Model identifier of requests must match the model identifier of the request!" }
        }
        if (modelInputs.size >= 2) {
            val unique = modelInputs.toSet()
            require(unique.size == modelInputs.size) {"There were duplicate model inputs in the request!"}
        }
    }

    /**
     *  A convenience constructor for making an evaluation request that has a single request.
     *  The CRN option is false by default because it is a single request. CRN is applied
     *  across multiple evaluations.
     */
    constructor(modelIdentifier: String, request: ModelInputs, cachingAllowed: Boolean = true) : this(
        modelIdentifier = modelIdentifier,
        modelInputs = listOf(request), cachingAllowed = cachingAllowed
    )

    override fun toString(): String {
        val sb = StringBuilder()

        return "Evaluation Request for ${modelInputs.size} inputs: modelIdentifier = $modelIdentifier : crnOption = $crnOption : cachingAllowed = cachingAllowed"
    }
}
