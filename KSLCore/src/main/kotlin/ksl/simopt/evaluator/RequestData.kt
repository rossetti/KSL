package ksl.simopt.evaluator

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import ksl.controls.experiments.ExperimentRunParameters


/**
 *  The data associated with a request for a simulation evaluation.  A critical aspect of this
 *  implementation is the equality of instances is determined.
 *
 *  Two instances of RequestData are considered equal if:
 *
 *  1. the [modelIdentifier] properties are equal, and
 *  2. the [responseNames] properties are equal (contain all the same response names), and
 *  3. the [inputs] properties are equal (contain the same (key, value) pairs)
 *
 *  @param modelIdentifier the model identifier associated with the simulation model that will be executed
 *  @param numReplications the number of replications to run the model. Must be greater than 0. This
 *  value will override a specification for the number of replications supplied by any experimental run parameters.
 *  @param inputs The input variable and its value for parameterizing the run of the simulation. If empty,
 *  the current values for the inputs will be used for the simulation.
 *  @param responseNames the names of the response variables requested from the simulation results.
 *  If no response names are provided, then all responses from the simulation will be returned. The default
 *  is all responses from the model.
 *  @param experimentRunParameters an optional set of simulation run parameters for application to the model.
 *  If not supplied, then the model's current (default) settings will be used.
 *  @param requestTime the instant that the request was constructed
 */
@Serializable
data class RequestData(
    val modelIdentifier: String,
    val numReplications: Int,
    val inputs: Map<String, Double> = emptyMap(),
    val responseNames: Set<String> = emptySet(),
    val requestTime: Instant = Clock.System.now()
) {
    init {
        require(modelIdentifier.isNotBlank()) { "Model identifier must not be blank" }
        require(numReplications > 0) { "Number of reps must be greater than zero" }
        for (name in responseNames) {
            require(name.isNotBlank()) { "Response names must not be blank" }
        }
        for (name in inputs.keys) {
            require(name.isNotBlank()) { "Input variable names must not be blank" }
        }
    }

    /**
     *  Creates a duplicate instance of the object with the specified number of
     *  replications.
     */
    fun instance(numReplications: Int): RequestData {
        require(numReplications > 0) { "Number of reps must be greater than zero" }
        //TODO this is being called within Evaluator when revising the request.
        return RequestData(
            modelIdentifier = this.modelIdentifier,
            numReplications = numReplications,
            inputs = this.inputs,
            responseNames = this.responseNames,
            requestTime = Clock.System.now()
        )
    }

    /**
     *  Equals is based on modelIdentifier, inputs, and response names
     *  all being the same.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RequestData

        if (modelIdentifier != other.modelIdentifier) return false
        if (responseNames != other.responseNames) return false
        if (inputs != other.inputs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = modelIdentifier.hashCode()
        result = 31 * result + inputs.hashCode()
        result = 31 * result + responseNames.hashCode()
        return result
    }

    /**
     *  The values of the input parameters as an array. The order
     *  of the array is based on the order of the input variables
     *  in the map of input variables.
     */
    val inputValues: DoubleArray
        get() = inputs.values.toDoubleArray()

}
