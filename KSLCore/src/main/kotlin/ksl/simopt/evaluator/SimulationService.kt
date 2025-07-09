package ksl.simopt.evaluator

import ksl.controls.experiments.ExperimentRunParameters
import ksl.controls.experiments.SimulationRun
import ksl.simopt.cache.MemorySimulationRunCache
import ksl.simopt.cache.SimulationRunCacheIfc
import ksl.simopt.evaluator.SimulationServiceIfc.Companion.executeSimulation
import ksl.simulation.MapModelProvider
import ksl.simulation.ModelBuilderIfc
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
open class SimulationService @JvmOverloads constructor(
    val modelProvider: ModelProviderIfc,
    val simulationRunCache: SimulationRunCacheIfc? = null,
    var useCachedSimulationRuns: Boolean = false
) : SimulationServiceIfc {

    /**
     * @param modelIdentifier the string identifier for the model to be executed
     * @return true if the service will provide results from the model
     */
    override fun isModelProvided(modelIdentifier: String): Boolean {
        return modelProvider.isModelProvided(modelIdentifier)
    }

    /**
     * Retrieves a list of model identifiers provided by the service. These identifiers represent
     * the models available for simulation runs or other operations.
     *
     * @return a list of strings where each string represents a unique model identifier.
     */
    override fun providedModels(): List<String> {
        return modelProvider.modelIdentifiers()
    }

    /**
     * Retrieves a list of response names associated with the specified model.
     *
     * @param modelIdentifier the identifier of the model whose response names are to be retrieved
     * @return a list of response names corresponding to the specified model
     */
    @Suppress("unused")
    override fun responseNames(modelIdentifier: String): List<String> {
        return modelProvider.responseNames(modelIdentifier)
    }

    /**
     * Retrieves the list of input names associated with the specified model.
     *
     * @param modelIdentifier the identifier of the model whose input names are to be retrieved
     * @return a list of strings representing the input names corresponding to the specified model
     */
    @Suppress("unused")
    override fun inputNames(modelIdentifier: String): List<String> {
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
    override fun experimentalParameters(modelIdentifier: String): ExperimentRunParameters {
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
    override fun runSimulation(request: RequestData): Result<SimulationRun> {
        if (!isModelProvided(request.modelIdentifier)) {
            val msg = "The SimulationService does not provide model ${request.modelIdentifier}\n" +
                    "request: $request"
            SimulationServiceIfc.logger.error { msg }
            return Result.failure(ModelNotProvidedException(msg))
        }
        // check the cache before working with the model
        var simulationRun = retrieveFromCache(request)
        if (simulationRun != null) {
            SimulationServiceIfc.logger.info { "SimulationService: results for ${request.modelIdentifier} returned from the cache" }
            return Result.success(simulationRun)
        }
        // not found in the cache, need to run the model
        val model = modelProvider.provideModel(request.modelIdentifier)
        simulationRun = executeSimulation(request, model)
        if (simulationRun.runErrorMsg.isNotEmpty()) {
            SimulationServiceIfc.logger.info { "SimulationService: Simulation for model: ${model.name} experiment: ${model.experimentName} had an error. " }
            SimulationServiceIfc.logger.info { "Error message: ${simulationRun.runErrorMsg} " }
            return Result.failure(SimulationRunException(simulationRun))
        } else {
            // only store good simulation runs in the cache
            // add the SimulationRun to the simulation run cache
            simulationRunCache?.put(request, simulationRun)
            if (simulationRunCache != null) {
                SimulationServiceIfc.logger.info { "SimulationService: results for ${request.modelIdentifier} added to the cache" }
            }
            return Result.success(simulationRun)
        }
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

    companion object {

        /**
         * Creates a cached simulation service instance using the provided model identifier and model builder.
         *
         * @param modelIdentifier the unique identifier for the model to be used in the simulation service
         * @param modelBuilder the builder responsible for constructing the model associated with the given identifier
         * @return a new instance of [SimulationService] configured with the specified model provider and cache settings
         */
        @JvmStatic
        fun createCachedSimulationServiceForModel(modelIdentifier: String, modelBuilder: ModelBuilderIfc) : SimulationService {
            return createCachedSimulationService(MapModelProvider(modelIdentifier, modelBuilder))
        }

        /**
         * Creates a new instance of a `SimulationService` with a memory-based cache for simulation runs.
         * This service uses the model provider and enables caching for simulation runs.
         *
         * @param modelProvider an instance of `ModelProviderIfc` responsible for providing models for simulation
         * @return an instance of [SimulationService] configured with a memory-based simulation run cache and caching enabled
         */
        @JvmStatic
        fun createCachedSimulationService(modelProvider: ModelProviderIfc) : SimulationService {
            return SimulationService(
                modelProvider = modelProvider,
                simulationRunCache = MemorySimulationRunCache(),
                useCachedSimulationRuns = true
            )
        }
    }

}

