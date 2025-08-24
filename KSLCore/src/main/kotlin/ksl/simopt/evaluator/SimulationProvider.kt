package ksl.simopt.evaluator

import ksl.controls.experiments.SimulationRun
import ksl.controls.experiments.SimulationRunner
import ksl.simopt.cache.SimulationRunCacheIfc
import ksl.simopt.evaluator.SimulationServiceIfc.Companion.logger
import ksl.simulation.Model

/**
 *  This simulation provider will execute evaluation requests on the same model
 *  and collect the desired responses.  This provider runs the model's replications
 *  locally and sequentially in the same execution thread as the requests.
 *
 *  Note that the model is reused.  This may cause issues in a parallel execution environment or
 *  if the model is mutated externally.  The secondary constructor will create a new model instance
 *  for use by the provider.  Use the primary constructor if you are certain that there are no issues with
 *  sharing the model instance.
 *
 * @param model a function that promises to create the model that will be executed. The model that is created
 *  is assumed to be configured to run.
 * @param simulationRunCache if supplied the cache will be used to store executed simulation runs.
 */
@Suppress("unused")
class SimulationProvider internal constructor(
    internal var model: Model,
    override val simulationRunCache: SimulationRunCacheIfc? = null,
) : SimulationProviderIfc {

    /**
     *  An identifier for the model. By default, this is the [modelIdentifier] property of model.
     * This property is used to ensure that simulation execution requests are intended for the associated model.
     */
    val modelIdentifier: String
        get() = model.modelIdentifier

    /**
     * Secondary constructor for the SimulationProvider class.
     *
     * @param modelCreator A lambda function that creates and returns a Model instance. It provides the primary model for the simulation.
     * @param simulationRunCache if supplied the cache will be used to store executed simulation runs.
     */
    constructor(
        modelCreator: () -> Model,
        simulationRunCache: SimulationRunCacheIfc? = null,
    ) : this(
        model = modelCreator(),
        simulationRunCache = simulationRunCache,
    )

    private val mySimulationRunner = SimulationRunner(model)

    override fun isModelValid(modelIdentifier: String): Boolean {
        return model.modelIdentifier == modelIdentifier
    }

    override fun areInputNamesValid(inputNames: Set<String>): Boolean {
        return model.validateInputKeys(inputNames)
    }

    override fun areResponseNamesValid(responseNames: Set<String>): Boolean {
        return model.validateResponseNames(responseNames)
    }

    override fun simulate(evaluationRequest: EvaluationRequest): Map<ModelInputs, Result<ResponseMap>> {
        require(modelIdentifier == evaluationRequest.modelIdentifier) { "The model identifier from the request must match the provider's model identifier." }
        val originalExpRunParams = model.extractRunParameters()
        // The evaluation request has options for caching and CRN that need to be handled
        if (evaluationRequest.crnOption || !evaluationRequest.cachingAllowed || (simulationRunCache == null)) {
            // CRN should not permit cache retrieval. There is no way to ensure that the simulation runs
            // in the cache used CRN and saving dependent samples in the cache is problematic.
            // If there is no caching allowed or there isn't a cache, we just do the simulations.
            if (evaluationRequest.crnOption) {
                model.resetStartStreamOption = true
            }
            val results = simulateWithoutCache(evaluationRequest.modelInputs)
            model.changeRunParameters(originalExpRunParams)
            return results
        }
        // There is a cache and we are permitted to use it.
        val allResults = mutableMapOf<ModelInputs, Result<ResponseMap>>()
        for (modelInputs in evaluationRequest.modelInputs) {
            var simulationRun = useCachedSimulationRun(modelInputs)
            if (simulationRun == null) {
                simulationRun = executeSimulation(modelInputs, model)
            }
            val results = captureResultFromSimulationRun(modelInputs, simulationRun)
            allResults[modelInputs]= results
        }
        model.changeRunParameters(originalExpRunParams)
        return allResults
    }

    private fun simulateWithoutCache(modelInputs: List<ModelInputs>): Map<ModelInputs, Result<ResponseMap>> {
        val allResults = mutableMapOf<ModelInputs, Result<ResponseMap>>()
        for (modelInputs in modelInputs) {
            val simulationRun = executeSimulation(modelInputs, model)
            val results = captureResultFromSimulationRun(modelInputs, simulationRun)
            allResults[modelInputs] = results
        }
        return allResults
    }

    private fun useCachedSimulationRun(modelInputs: ModelInputs): SimulationRun? {
        if (simulationRunCache == null) return null
        if (simulationRunCache.containsKey(modelInputs)) {
            // check if it has the appropriate number of replications
            val requestedReplications = modelInputs.numReplications
            val simulationRun = simulationRunCache[modelInputs]!!
            if (requestedReplications <= simulationRun.numberOfReplications) {
                return simulationRun
            }
        }
        return null
    }

    companion object {

        /**
         * Converts the data within the [SimulationRun] to a [ResponseMap] and ensures that
         * the model inputs are associated with the responses.
         *
         * The method processes the simulation results to estimate and map the responses
         * specified in the request. If the request specifies no response names, all
         * responses from the simulation run are included in the result map.
         *
         * @param modelInputs the model input data containing model identifier, inputs,
         *                response names, and additional parameters necessary for simulation evaluation
         * @param simulationRun the simulation execution results containing the data to be
         *                      processed and mapped
         * @return a map where the key is the given model inputs and the value is a ResponseMap
         *         containing the estimated simulation responses for the requested response names.
         *         If the simulation run has an error, then the ResponseMap is mapped to a failed Result.
         * @throws IllegalArgumentException if a specified response name in the model inputs
         *                                  does not exist in the simulation results
         */
        fun captureResultFromSimulationRun(
            modelInputs: ModelInputs,
            simulationRun: SimulationRun,
        ): Result<ResponseMap> {
            if (simulationRun.runErrorMsg.isNotEmpty()) {
                return Result.failure(SimulationRunException(simulationRun))
            }
            // extract the replication data for each simulation response
            val responseMap = simulationRunToResponseMap(modelInputs, simulationRun)
            // capture the responses for the request
            return Result.success(responseMap)
        }

        /**
         * Converts the data within the [SimulationRun] to a [ResponseMap] and ensures that
         * the model inputs are associated with the responses.
         *
         * The method processes the simulation results to estimate and map the responses
         * specified in the model inputs. If the model inputs specifies no response names, all
         * responses from the simulation run are included in the result map.
         *
         * @param modelInputs the request data containing model identifier, inputs,
         *                response names, and additional parameters necessary for simulation evaluation
         * @param simulationRun the simulation execution results containing the data to be
         *                      processed and mapped
         * @return a [ResponseMap] holding the results from the simulation run
         * @throws IllegalArgumentException if a specified response name in the model inputs
         *                                  does not exist in the simulation results
         *  @throws IllegalArgumentException if the provided simulation run has an error.
         */
        fun simulationRunToResponseMap(
            modelInputs: ModelInputs,
            simulationRun: SimulationRun
        ): ResponseMap {
            require(simulationRun.runErrorMsg.isEmpty()) { "The simulation run had an error: ${simulationRun.runErrorMsg}" }
            // extract the replication data for each simulation response
            val replicationData = simulationRun.results
            // if the request's response name set is empty then return all responses from the simulation run
            val responseNames = modelInputs.responseNames.ifEmpty {
                simulationRun.results.keys
            }
            // make an empty response map to hold the estimated responses
            val responseMap = ResponseMap(modelInputs.modelIdentifier, responseNames)
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

        /**
         * Executes a simulation using the given model inputs. It updates the model's parameters
         * based on the request data and runs the simulation. Thus, a side effect of this function
         * is to update the model's experimental run parameters.  Thus, you may want to capture
         * the current experimental run parameters before calling this function.
         *
         * @param modelInputs the request data containing the model identifier, inputs, number of replications,
         *                and optional simulation run parameters. It specifies how the simulation should be executed.
         * @param model the model to be used for the simulation. Includes the configuration and behavior required
         *              for the simulation execution.
         * @param expIdentifier a string that is used to uniquely identify the experiment within the context
         * of multiple executions for the same model. The name of the experiment will be:
         * "${request.modelIdentifier}_Exp_$expIdentifier". If expIdentifier is null, then the time of the request
         * is used to as expIdentifier. Depending on how users might store experimental
         * results, this naming may be important, especially if a KSLDatabase is used to hold experimental results.
         * @return the result of the simulation run encapsulated in a SimulationRun object. This contains the
         *         results from the executed simulation.  If the simulation run resulted in errors, the simulation run's
         *         runErrorMsg property will not be empty (blank).
         */
        @Suppress("unused")
        fun executeSimulation(modelInputs: ModelInputs, model: Model, expIdentifier: String? = null): SimulationRun {
            val srp = model.extractRunParameters()
            srp.experimentName = if (expIdentifier != null) "${modelInputs.modelIdentifier}_Exp_$expIdentifier"
            else "${modelInputs.modelIdentifier}_Exp_${modelInputs.requestTime}"
            // ensure that the requested number of replications will be executed
            srp.numberOfReplications = modelInputs.numReplications
            logger.info { "SimulationProvider: Running simulation for model: ${modelInputs.modelIdentifier} experiment: ${srp.experimentName} " }
            val mySimulationRunner = SimulationRunner(model)
            //run the simulation to produce the simulation run results
            val simulationRun = mySimulationRunner.simulate(
                modelIdentifier = modelInputs.modelIdentifier,
                inputs = modelInputs.inputs,
                experimentRunParameters = srp)
            logger.info { "SimulationProvider: Completed simulation for model: ${modelInputs.modelIdentifier} experiment: ${srp.experimentName} " }
            return simulationRun
        }
    }

}