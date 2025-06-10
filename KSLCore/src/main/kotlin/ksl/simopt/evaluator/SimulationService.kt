package ksl.simopt.evaluator

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.controls.experiments.ExperimentRunParameters
import ksl.controls.experiments.SimulationRun
import ksl.controls.experiments.SimulationRunner
import ksl.simopt.cache.SimulationRunCacheIfc
import ksl.simulation.Model
import ksl.simulation.ModelProviderIfc

/**
 *  This simulation service will execute evaluation requests on models
 *  and collect the desired responses.  This service runs the model's replications
 *  locally and sequentially in the same execution thread as the requests.
 *
 * @param modelProvider provides the models that are registered with this provider based on their model identifiers
 * @param simulationRunCache if supplied the cache will be used to store executed simulation runs.
 * @param useCachedSimulationRuns Indicates whether the service should use cached simulation runs when responding
 * to requests. The default is false. If the simulation runs are not cached, this option has no effect.
 */
@Suppress("unused")
open class SimulationService(
    val modelProvider: ModelProviderIfc,
    val simulationRunCache: SimulationRunCacheIfc? = null,
    var useCachedSimulationRuns: Boolean = false,
) {

    /**
     *  Used to count the number of times that the simulation model is executed. Each execution can
     *  be considered a different experiment
     */
    var executionCounter: Int = 0
        private set

    /**
     *  Causes the execution counter to be reset to 0. Care must be taken if a database is used to
     *  collect simulation results. The names of the experiments are based on the value of the counter. An
     *  error will occur if multiple experiments have the same name in the database. You will likely want
     *  to export and clear the data from the database before running additional simulations.
     */
    @Suppress("unused")
    fun resetExecutionCounter() {
        executionCounter = 0
    }

    /**
     * @param modelIdentifier the string identifier for the model to be executed
     * @return true if the service will provide results from the model
     */
    fun isModelProvided(modelIdentifier: String): Boolean {
        return modelProvider.isModelProvided(modelIdentifier)
    }

    /**
     * Retrieves a list of model identifiers provided by the service. These identifiers represent
     * the models available for simulation runs or other operations.
     *
     * @return a list of strings where each string represents a unique model identifier.
     */
    fun providedModels(): List<String> {
        return modelProvider.modelIdentifiers()
    }

    /**
     * Retrieves a list of response names associated with the specified model.
     *
     * @param modelIdentifier the identifier of the model whose response names are to be retrieved
     * @return a list of response names corresponding to the specified model
     */
    @Suppress("unused")
    fun responseNames(modelIdentifier: String): List<String> {
        return modelProvider.responseNames(modelIdentifier)
    }

    /**
     * Retrieves the list of input names associated with the specified model.
     *
     * @param modelIdentifier the identifier of the model whose input names are to be retrieved
     * @return a list of strings representing the input names corresponding to the specified model
     */
    @Suppress("unused")
    fun inputNames(modelIdentifier: String): List<String> {
        return modelProvider.inputNames(modelIdentifier)
    }

    /**
     * Retrieves the experimental run parameters for the model identified by the given identifier.
     * This method extracts detailed configurations and settings required to execute the experiment.
     *
     * @param modelIdentifier the identifier of the model whose experimental parameters are to be retrieved
     * @return an instance of [ExperimentRunParameters] containing the run parameters for the specified model
     */
    @Suppress("unused")
    fun experimentalParameters(modelIdentifier: String): ExperimentRunParameters {
        return modelProvider.experimentalParameters(modelIdentifier)
    }

    /**
     * Executes a simulation run based on the given request data. This method retrieves simulation results
     * from the cache if available, or executes the simulation using the provided model. If the simulation
     * completes successfully, the result is cached for future requests. If the simulation results
     * in an error, an exception is returned with the error details.
     *
     * @param request the request data containing the model identifier, inputs, number of replications,
     * and other parameters necessary to execute the simulation
     * @return a result object wrapping a successful simulation run or an exception if the simulation fails
     */
    fun runSimulation(request: RequestData): Result<SimulationRun> {
        if (!isModelProvided(request.modelIdentifier)) {
            val msg = "The SimulationService does not provide model ${request.modelIdentifier}\n" +
                    "request: $request"
            logger.error { msg }
            return Result.failure(ModelNotProvidedException(msg))
        }
        // check the cache before working with the model
        var simulationRun = retrieveFromCache(request)
        if (simulationRun != null) {
            logger.info { "SimulationService: results for ${request.modelIdentifier} returned from the cache" }
            return Result.success(simulationRun)
        }
        // not found in the cache, need to run the model
        val model = modelProvider.provideModel(request.modelIdentifier)
        simulationRun = executeSimulation(request, model)
        if (simulationRun.runErrorMsg.isNotEmpty()) {
            logger.info { "SimulationService: Simulation for model: ${model.name} experiment: ${model.experimentName} had an error. " }
            logger.info { "Error message: ${simulationRun.runErrorMsg} " }
            return Result.failure(SimulationRunException(simulationRun))
        } else {
            // only store good simulation runs in the cache
            // add the SimulationRun to the simulation run cache
            simulationRunCache?.put(request, simulationRun)
            return Result.success(simulationRun)
        }
    }


    /**
     * Executes a simulation based on the provided request data and maps the results into a ResponseMap.
     * If the simulation runs successfully, the request is associated with the ResponseMap in the returned map.
     * In case of a failure during the simulation, an error is returned instead.
     *
     * @param request the request data containing model identifiers, inputs, response names,
     *                and parameters necessary to execute and evaluate the simulation
     * @return a result wrapping a map where each key is the provided request, and the value
     *         is the corresponding ResponseMap. If the simulation fails, the result contains an error.
     */
    fun runSimulationToResponseMap(request: RequestData): Result<ResponseMap> {
        val simulationRunResult = runSimulation(request)
        simulationRunResult.onFailure {
            return Result.failure(it)
        }
        return Result.success(
            simulationRunToResponseMap(
                request,
                simulationRunResult.getOrNull()!!
            )
        )
    }

    /**
     * Executes multiple simulations based on the provided list of request data and maps each request
     * to a corresponding ResponseMap. Each request is processed individually, and the results of the
     * simulations are stored as a key-value pair in the returned map. If the input list is empty, an
     * exception is thrown.
     *
     * @param requests the list of RequestData objects representing the simulation requests to be processed.
     *                 The list must contain at least one element.
     * @return a map where each key is a RequestData object and each value is a Result wrapping either
     *         a successful ResponseMap or an exception if the simulation fails for the corresponding request.
     * @throws IllegalArgumentException if the input list of requests is empty.
     */
    fun runSimulationsToResponseMaps(requests: List<RequestData>): Map<RequestData, Result<ResponseMap>> {
        require(requests.isNotEmpty()) { "The supplied list of requests was empty!" }
        val resultMap = mutableMapOf<RequestData, Result<ResponseMap>>()
        //TODO in theory these could be run "in parallel"
        for (request in requests) {
            resultMap[request] = runSimulationToResponseMap(request)
        }
        return resultMap
    }

    /**
     * Executes multiple simulation runs based on the provided list of request data. Each request is
     * processed individually, and the result is recorded and returned as a map where the key is the
     * request and the value is the result of the simulation. If the input list of requests is empty,
     * an exception is thrown.
     *
     * @param requests the list of RequestData objects, each representing a simulation request to be executed.
     *                 The list must not be empty.
     * @return a map where each key is a RequestData object and each value is a Result wrapping a successful
     *         SimulationRun or an exception if the simulation for that request fails.
     * @throws IllegalArgumentException if the input list of requests is empty.
     */
    open fun runSimulations(requests: List<RequestData>): Map<RequestData, Result<SimulationRun>> {
        require(requests.isNotEmpty()) { "The supplied list of requests was empty!" }
        val resultMap = mutableMapOf<RequestData, Result<SimulationRun>>()
        //TODO in theory these could be run "in parallel"
        for (request in requests) {
            resultMap[request] = runSimulation(request)
        }
        return resultMap
    }

    /**
     * Retrieves a cached simulation run based on the provided request data.
     * This method checks if caching is enabled and if the requested simulation run exists in the cache
     * with the required number of replications. If any condition is not met, it returns null.
     *
     * @param request the request data containing the parameters to identify the desired simulation run,
     * including the number of requested replications.
     * @return the cached simulation run if it exists, meets the requirements, and caching is enabled;
     * otherwise, null.
     */
    protected fun retrieveFromCache(request: RequestData): SimulationRun? {
        if (simulationRunCache == null) {
            return null // no cache, return null
        }
        if (!useCachedSimulationRuns) {
            return null // don't use the cache, return null
        }
        val simulationRun = simulationRunCache[request]
        if (simulationRun == null) {
            return null // run not found in the cache, return null
        }
        val requestedReplications = request.numReplications
        return if (requestedReplications <= simulationRun.numberOfReplications) {
            simulationRun
        } else {
            null // not enough replications stored in the cache, return null
        }
    }

    /**
     * Executes a simulation using the given request data and model. It updates the model's parameters
     * based on the request data, runs the simulation, and resets the model's parameters to their
     * original state after execution.
     *
     * @param request the request data containing the model identifier, inputs, number of replications,
     *                and optional simulation run parameters. It specifies how the simulation should be executed.
     * @param model the model to be used for the simulation. Includes the configuration and behavior required
     *              for the simulation execution.
     * @return the result of the simulation run encapsulated in a SimulationRun object. This contains the
     *         results from the executed simulation.
     */
    protected open fun executeSimulation(request: RequestData, model: Model): SimulationRun {
        executionCounter++
        val myOriginalExpRunParams = model.extractRunParameters()
        // update experiment name on the model and number of replications
        model.experimentName = request.modelIdentifier + "_Exp_$executionCounter"
        model.numberOfReplications = request.numReplications
        if (request.experimentRunParameters != null) {
            // update the name and the number of replications on the supplied parameters
            request.experimentRunParameters.experimentName = model.experimentName
            request.experimentRunParameters.numberOfReplications = model.numberOfReplications
        }
        logger.info { "SimulationService: Running simulation for model: ${model.name} experiment: ${model.experimentName} " }
        //TODO in theory the replications might be run in parallel
        val mySimulationRunner = SimulationRunner(model)
        //run the simulation
        val simulationRun = mySimulationRunner.simulate(
            request.inputs,
            request.experimentRunParameters ?: myOriginalExpRunParams
        )
        logger.info { "SimulationService: Completed simulation for model: ${model.name} experiment: ${model.experimentName} " }
        // reset the model run parameters back to their original values
        model.changeRunParameters(myOriginalExpRunParams)
        return simulationRun
    }

    /**
     * Associates a given request with a ResponseMap from a simulation run.
     * The method processes the simulation results to estimate and map the responses
     * specified in the request. If the request specifies no response names, all
     * responses from the simulation run are included in the result map.
     *
     * @param request the request data containing model identifier, inputs,
     *                response names, and additional parameters necessary for simulation evaluation
     * @param simulationRun the simulation execution results containing the data to be
     *                      processed and mapped
     * @return a map where the key is the given request and the value is a ResponseMap
     *         containing the estimated simulation responses for the requested response names
     * @throws IllegalArgumentException if a specified response name in the request
     *                                  does not exist in the simulation results
     *  @throws IllegalArgumentException if the provided simulation run has an error.
     */
    fun simulationRunToResponseMap(
        request: RequestData,
        simulationRun: SimulationRun
    ): ResponseMap {
        require(simulationRun.runErrorMsg.isEmpty()) { "The simulation run had an error: ${simulationRun.runErrorMsg}" }
        // extract the replication data for each simulation response
        val replicationData = simulationRun.results
        // if the request's response name set is empty then return all responses from the simulation run
        val responseNames = request.responseNames.ifEmpty {
            simulationRun.results.keys
        }
        // make an empty response map to hold the estimated responses
        val responseMap = ResponseMap(request.modelIdentifier, responseNames)
        // fill the response map
        for (name in responseNames) {
            // this should have been checked when validating the request
            require(replicationData.containsKey(name)) { "The simulation responses did not contain the requested response name $name" }
            // get the data from the simulation
            val data = replicationData[name]!!
            // compute the estimates from the replication data
            val estimatedResponse = EstimatedResponse(name, data)
            // place the estimate in the response map
            responseMap.add(estimatedResponse)
        }
        // return the responses for the request
        return responseMap
    }

    companion object {
        val logger: KLogger = KotlinLogging.logger {}
    }

}

class ModelNotProvidedException(
    message: String? = null,
    cause: Throwable? = null
) : Exception(message, cause)

class SimulationRunException(
    val simulationRun: SimulationRun,
    message: String? = simulationRun.runErrorMsg,
    cause: Throwable? = null
) : Exception(message, cause)