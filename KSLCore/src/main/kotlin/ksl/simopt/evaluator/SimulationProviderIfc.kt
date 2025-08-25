package ksl.simopt.evaluator

import ksl.simopt.cache.SimulationRunCacheIfc

/**
 *  An interface that promises to run simulations on
 *  instances of input/output pairs. The keys of the map are
 *  evaluation requests for a specific number of replications for
 *  specific input variable values. The associated ResponseMap
 *  represents the desired responses from the simulation. It should
 *  contain the replication averages for each desired response.
 */
interface SimulationProviderIfc : SimulationOracleIfc {

    /**
     *  Use to hold executed simulation runs.
     */
    val simulationRunCache: SimulationRunCacheIfc?

    /**
     *  Indicates if the model identified by the modelIdentifier is valid. That is,
     *  this provider can perform simulation runs on the model.
     *
     *  @param modelIdentifier the identifier of the model. This should be the unique name of the model.
     */
    fun isModelValid(modelIdentifier: String): Boolean

    /**
     *  Indicates if the input names are valid. The input names are valid if
     *  they are empty. If they are not empty, then they must be a subset of the input names
     *  associated with the model.
     *
     *  @param inputNames the names of the responses
     */
    fun areInputNamesValid(inputNames: Set<String>): Boolean

    /**
     *  Indicates if the response names are valid. The response names are valid if
     *  they are empty. If they are not empty, then they must be a subset of the response names
     *  associated with the model.
     *
     *  @param responseNames the names of the responses
     */
    fun areResponseNamesValid(responseNames: Set<String>): Boolean

    /**
     *  Indicates if the request is valid. The request is valid if
     *  1. The model identifier is valid.
     *  2. The input names are valid.
     *  3. Response names are valid.
     *
     *  Empty input names and response names are valid.
     *  Input names or response names that are not associated with the model are not valid.
     *
     *  @param request the request to validate. If the input names and response names are not specified,
     *  then the current input settings of the model will be used and all responses from the simulation will be returned.
     */
    @Suppress("unused")
    fun isRequestValid(request: ModelInputs): Boolean {
        if (!isModelValid(request.modelIdentifier)) return false
        // check input names
        if (!areInputNamesValid(request.inputs.keys)) return false
        // inputs are empty or valid
        if (!areResponseNamesValid(request.responseNames)) return false
        return true
    }

}