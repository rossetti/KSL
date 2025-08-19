package ksl.simopt.evaluator

import kotlinx.serialization.Serializable

/**
 *  An evaluation request is used to request simulation oracle execution on a model.
 *
 *  @param modelIdentifier the identifier for the model that will execute the requests
 *  @param requests the request (input/output) for the model to use
 *  @param crnOption indicates if the requests should be executed by the model using
 *  common random numbers. The default is false.
 */
@Serializable
data class EvaluationRequest(
    val modelIdentifier: String,
    val requests: List<ModelInputs>,
    val crnOption: Boolean = false
) {
    init {
        require(requests.isNotEmpty()) { "At least one request must be defined" }
        if (requests.size == 1) {
            require(crnOption == false) {"The crnOption must be false when there is only one request" }
        }
        for (request in requests) {
            require(request.modelIdentifier == modelIdentifier) { "Model identifier of requests must match the model identifier of the request" }
        }
    }

    /**
     *  A convenience constructor for making an evaluation request that has a single request.
     *  The CRN option is false by default because it is a single request. CRN is applied
     *  across multiple evaluations.
     */
    constructor(modelIdentifier: String, request: ModelInputs,) : this(
        modelIdentifier = modelIdentifier,
        requests = listOf(request),
    )
}
