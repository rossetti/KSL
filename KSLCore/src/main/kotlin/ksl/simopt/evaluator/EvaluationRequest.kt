package ksl.simopt.evaluator

import kotlinx.serialization.Serializable

/**
 *  An evaluation request is used to request simulation oracle execution on a model.
 *
 *  @param modelIdentifier the identifier for the model that will execute the requests
 *  @param modelInputs the request (input/output) for the model to use. There must not
 *  be duplicates in this list as defined by the equality of model inputs.  All model inputs
 *  must be associated with the same model identifier.
 *  @param crnOption indicates if the requests should be executed by the model using
 *  common random numbers. The default is false.  The CRN option cannot be true if there
 *  is only one model input to be evaluated. CRN only makes sense with 2 or more evaluations.
 *  @param cachingAllowed indicates if the request permits evaluation via a cache. The default
 *  is true. If the CRN option is true, caching must be false.
 */
@Serializable
data class EvaluationRequest @JvmOverloads constructor (
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
    @JvmOverloads
    @Suppress("unused")
    constructor(modelIdentifier: String, modelInputs: ModelInputs, cachingAllowed: Boolean = true) : this(
        modelIdentifier = modelIdentifier,
        modelInputs = listOf(modelInputs), cachingAllowed = cachingAllowed
    )

    /**
     *  Creates a new EvaluationRequest with the same model identifier, CRN option, and caching options
     *  as this instance, but with the supplied model inputs.
     *  @param modelInputs the list of model inputs for the evaluation request
     */
    fun instance(modelInputs: List<ModelInputs>) : EvaluationRequest {
        return EvaluationRequest(this.modelIdentifier, modelInputs, this.crnOption, this.cachingAllowed)
    }

    override fun toString(): String {
        return "Evaluation Request for ${modelInputs.size} inputs: modelIdentifier = $modelIdentifier : crnOption = $crnOption : cachingAllowed = cachingAllowed"
    }
}
