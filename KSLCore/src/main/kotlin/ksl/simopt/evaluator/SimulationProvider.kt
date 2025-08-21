package ksl.simopt.evaluator

import ksl.controls.experiments.ExperimentRunParameters
import ksl.controls.experiments.SimulationRun
import ksl.controls.experiments.SimulationRunner
import ksl.simopt.cache.SimulationRunCacheIfc
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
 * @param modelIdentifier an identifier for the model. By default, this is the [modelIdentifier] property of model.
 * This property is used to ensure that simulation execution requests are intended for the associated model.
 * @param simulationRunCache if supplied the cache will be used to store executed simulation runs.
 */
@Suppress("unused")
class SimulationProvider internal constructor(
    val model: Model,
    val modelIdentifier: String = model.modelIdentifier,
    override val simulationRunCache: SimulationRunCacheIfc? = null,
) : SimulationProviderIfc {

    /**
     * Secondary constructor for the SimulationProvider class.
     *
     * @param modelCreator A lambda function that creates and returns a Model instance. It provides the primary model for the simulation.
     * @param simulationRunCache if supplied the cache will be used to store executed simulation runs.
     */
    constructor(
        modelCreator: () -> Model,
        modelIdentifier: String,
        simulationRunCache: SimulationRunCacheIfc? = null,
    ) : this(
        model = modelCreator(),
        modelIdentifier = modelIdentifier,
        simulationRunCache = simulationRunCache,
    )

    private val mySimulationRunner = SimulationRunner(model)

    /**
     *  capture the original experiment run parameters so that they can
     *  be restored to the original after executing a simulation
     */
    private val myOriginalExpRunParams: ExperimentRunParameters = model.extractRunParameters()

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
        // The evaluation request has options for caching and CRN that need to be handled
        if (evaluationRequest.crnOption || !evaluationRequest.cachingAllowed || (simulationRunCache == null)) {
            // CRN should not permit cache retrieval. There is no way to ensure that the simulation runs
            // in the cache used CRN and saving dependent samples in the cache is problematic.
            // If there is no caching allowed or there isn't a cache, we just do the simulations.
            if (evaluationRequest.crnOption) {
                model.resetStartStreamOption = true
            }
            return simulate(evaluationRequest.modelInputs)
        }
        // There is a cache and we are permitted to use it.
        val allResults = mutableMapOf<ModelInputs, Result<ResponseMap>>()
        for (modelInputs in evaluationRequest.modelInputs) {
            val simulationRun = useCachedSimulationRun(modelInputs) ?: executeSimulation(modelInputs)
            val results = captureResultsFromSimulationRun(modelInputs, simulationRun)
            allResults.putAll(results)
        }
        model.changeRunParameters(myOriginalExpRunParams)
        return allResults
    }

    private fun simulate(modelInputs: List<ModelInputs>): Map<ModelInputs, Result<ResponseMap>> {
        val allResults = mutableMapOf<ModelInputs, Result<ResponseMap>>()
        for (request in modelInputs) {
            val simulationRun = executeSimulation(request)
            val results = captureResultsFromSimulationRun(request, simulationRun)
            allResults.putAll(results)
        }
        model.changeRunParameters(myOriginalExpRunParams)
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

    private fun executeSimulation(
        request: ModelInputs,
    ): SimulationRun {
        executionCounter++
        // update experiment name on the model and number of replications
        model.experimentName = request.modelIdentifier + "_Exp_$executionCounter"
        model.numberOfReplications = request.numReplications
        Model.logger.info { "SimulationProvider: Running simulation for experiment: ${model.experimentName} " }
        //run the simulation
        val simulationRun = mySimulationRunner.simulate(
            modelIdentifier = request.modelIdentifier,
            inputs = request.inputs,
        )
        Model.logger.info { "SimulationProvider: Completed simulation for experiment: ${model.experimentName} " }
        // reset the model run parameters back to their original values
        model.changeRunParameters(myOriginalExpRunParams)
        return simulationRun
    }

    companion object {

        /**
         * Associates a given request with a ResponseMap from a simulation run.
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
        fun captureResultsFromSimulationRun(
            modelInputs: ModelInputs,
            simulationRun: SimulationRun,
        ): MutableMap<ModelInputs, Result<ResponseMap>> {
            val results = mutableMapOf<ModelInputs, Result<ResponseMap>>()
            if (simulationRun.runErrorMsg.isNotEmpty()) {
                results[modelInputs] = Result.failure(SimulationRunException(simulationRun))
                return results
            }
            // extract the replication data for each simulation response
            val responseMap = simulationRunToResponseMap(modelInputs, simulationRun)
            // capture the responses for each request
            results[modelInputs] = Result.success(responseMap)
            return results
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
         * @throws IllegalArgumentException if a specified response name in the model inputs
         *                                  does not exist in the simulation results
         *  @throws IllegalArgumentException if the provided simulation run has an error.
         */
        fun simulationRunToResponseMap(
            request: ModelInputs,
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
    }

}