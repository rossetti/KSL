package ksl.simopt.evaluator

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.controls.experiments.ExperimentRunParameters
import ksl.controls.experiments.SimulationRun
import ksl.controls.experiments.SimulationRunner
import ksl.simopt.evaluator.SimulationProvider.Companion.simulationRunToResponseMap
import ksl.simulation.Model

/**
 *  This simulation service will execute evaluation requests on models
 *  and collect the desired responses.
 *
 */
interface SimulationServiceIfc : SimulationOracleIfc {

    /**
     * @param modelIdentifier the string identifier for the model to be executed
     * @return true if the service will provide results from the model
     */
    fun isModelProvided(modelIdentifier: String): Boolean

    /**
     * Retrieves a list of model identifiers provided by the service. These identifiers represent
     * the models available for simulation runs or other operations.
     *
     * @return a list of strings where each string represents a unique model identifier.
     */
    fun providedModels(): List<String>

    /**
     * Retrieves a list of response names associated with the specified model.
     *
     * @param modelIdentifier the identifier of the model whose response names are to be retrieved
     * @return a list of response names corresponding to the specified model
     */
    fun responseNames(modelIdentifier: String): List<String>

    /**
     * Retrieves the list of input names associated with the specified model.
     *
     * @param modelIdentifier the identifier of the model whose input names are to be retrieved
     * @return a list of strings representing the input names corresponding to the specified model
     */
    fun inputNames(modelIdentifier: String): List<String>

    /**
     * Retrieves the experimental run parameters for the model identified by the given identifier.
     * This method extracts detailed configurations and settings required to execute the experiment.
     *
     * @param modelIdentifier the identifier of the model whose experimental parameters are to be retrieved
     * @return an instance of [ExperimentRunParameters] containing the run parameters for the specified model
     */
    fun experimentalParameters(modelIdentifier: String): ExperimentRunParameters

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
    fun runSimulation(request: ModelInputs): Result<SimulationRun>

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
    fun runSimulationToResponseMap(request: ModelInputs): Result<ResponseMap> {
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
     * exception is thrown.  This default implementation runs all the requests sequentially based
     * on the order of the supplied list.
     *
     * @param requests the list of RequestData objects representing the simulation requests to be processed.
     *                 The list must contain at least one element.
     * @return a map where each key is a RequestData object and each value is a Result wrapping either
     *         a successful ResponseMap or an exception if the simulation fails for the corresponding request.
     * @throws IllegalArgumentException if the input list of requests is empty.
     */
    @Suppress("unused")
    private fun simulate(requests: List<ModelInputs>): Map<ModelInputs, Result<ResponseMap>> {
        require(requests.isNotEmpty()) { "The supplied list of requests was empty!" }
        val resultMap = mutableMapOf<ModelInputs, Result<ResponseMap>>()
        for (request in requests) {
            resultMap[request] = runSimulationToResponseMap(request)
        }
        return resultMap
    }

    override fun simulate(evaluationRequest: EvaluationRequest): Map<ModelInputs, Result<ResponseMap>> {
        TODO("Not yet implemented")
    }

    /**
     * Executes multiple simulation runs based on the provided list of request data. Each request is
     * processed individually, and the result is recorded and returned as a map where the key is the
     * request and the value is the result of the simulation. If the input list of requests is empty,
     * an exception is thrown. This default implementation runs all the requests sequentially based
     * on the order of the supplied list.
     *
     * @param requests the list of RequestData objects, each representing a simulation request to be executed.
     *                 The list must not be empty.
     * @return a map where each key is a RequestData object and each value is a Result wrapping a successful
     *         SimulationRun or an exception if the simulation for that request fails.
     * @throws IllegalArgumentException if the input list of requests is empty.
     */
    @Suppress("unused")
    fun runSimulations(requests: List<ModelInputs>): Map<ModelInputs, Result<SimulationRun>> {
        require(requests.isNotEmpty()) { "The supplied list of requests was empty!" }
        val resultMap = mutableMapOf<ModelInputs, Result<SimulationRun>>()
        for (request in requests) {
            resultMap[request] = runSimulation(request)
        }
        return resultMap
    }

    companion object {
        val logger: KLogger = KotlinLogging.logger {}

        /**
         * Executes a simulation using the given request data and model. It updates the model's parameters
         * based on the request data, runs the simulation, and resets the model's parameters to their
         * original state after execution.  This default implementation executes each replication of the
         * request sequentially.
         *
         * @param request the request data containing the model identifier, inputs, number of replications,
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
        fun executeSimulation(request: ModelInputs, model: Model, expIdentifier: String? = null): SimulationRun {
            val myOriginalExpRunParams = model.extractRunParameters()
//            val srp = if (request.experimentRunParameters != null) {
//                // assume that if the user supplied the parameters then the experiment name is appropriate
//                request.experimentRunParameters
//            } else {
//                // If no experiment run parameters were provided with the request, then use the model's defaults.
//                val p = model.extractRunParameters()
//                p.experimentName = if (expIdentifier != null) "${request.modelIdentifier}_Exp_$expIdentifier"
//                    else "${request.modelIdentifier}_Exp_${request.requestTime}"
//                p
//            }
//            // If no experiment run parameters were provided with the request, then use the model's defaults.
            val srp = model.extractRunParameters()
            srp.experimentName = if (expIdentifier != null) "${request.modelIdentifier}_Exp_$expIdentifier"
            else "${request.modelIdentifier}_Exp_${request.requestTime}"
            // ensure that the requested number of replications will be executed
            srp.numberOfReplications = request.numReplications
            logger.info { "SimulationService: Running simulation for model: ${request.modelIdentifier} experiment: ${srp.experimentName} " }
            val mySimulationRunner = SimulationRunner(model)
            //run the simulation to produce the simulation run results
            val simulationRun = mySimulationRunner.simulate(
                modelIdentifier = request.modelIdentifier,
                inputs = request.inputs,
                experimentRunParameters = srp)
            logger.info { "SimulationService: Completed simulation for model: ${request.modelIdentifier} experiment: ${srp.experimentName} " }
            // reset the model run parameters back to their original values
            model.changeRunParameters(myOriginalExpRunParams)
            return simulationRun
        }

    }

}

