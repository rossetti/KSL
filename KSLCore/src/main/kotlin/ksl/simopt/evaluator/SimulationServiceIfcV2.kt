package ksl.simopt.evaluator

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.controls.experiments.ExperimentRunParameters
import ksl.controls.experiments.SimulationRun
import ksl.controls.experiments.SimulationRunner
import ksl.simopt.evaluator.SimulationProvider.Companion.simulationRunToResponseMap
import ksl.simulation.Model
import ksl.simulation.ModelDescriptor

/**
 *  This simulation service will execute evaluation requests on models
 *  and collect the desired responses.
 *
 */
interface SimulationServiceIfcV2 : SimulationOracleIfc {

    /**
     *  The model descriptors associated with the models served by the service.
     *  @return the list of model descriptors wrapped in a Result
     */
    fun modelDescriptors(): Result<List<ModelDescriptor>>

    /**
     * Executes a single simulation run based on the given model input. The simulation will be based on an
     * independent execution of the simulation model.  There will be no caching or common random
     * numbers used. If the simulation results in an error, an exception is returned with the error details
     * as part of the [Result] instance.
     *
     * @param modelInputs the model input data containing the model identifier, inputs, number of replications,
     * and other parameters necessary to execute the simulation
     * @return a result object wrapping a successful simulation run or an exception if the simulation fails
     */
    fun runSimulation(modelInputs: ModelInputs): Result<SimulationRun>

    /**
     * Executes multiple simulations based on the provided evaluation request and maps each request
     * to a corresponding [SimulationRun]. Each request is processed individually, and the results of the
     * simulations are stored as a key-value pair in the returned map. This default implementation runs all
     * the requests sequentially based on the order within the evaluation request.
     *
     * @param evaluationRequest the request for simulation evaluations
     * @return a map where each key is a ModelInputs object and each value is a Result wrapping either
     *         a successful SimulationRun or an exception if the simulation fails for the corresponding evaluation.
     * @throws IllegalArgumentException if the input list of evaluations is empty.
     */
    fun runSimulations(evaluationRequest: EvaluationRequest): Map<ModelInputs, Result<SimulationRun>>

    /**
     * Executes a single simulation run based on the given model input. The simulation will be based on an
     * independent execution of the simulation model.  There will be no caching or common random
     * numbers used. If the simulation results in an error, an exception is returned with the error details
     * as part of the [Result] instance.  This functionality is essentially the same as the function,
     * runSimulation(), except that the result wraps a [ResponseMap] rather than a [SimulationRun].
     *
     * @param modelInputs the request data containing model identifiers, inputs, response names,
     *                and parameters necessary to execute and evaluate the simulation
     * @return a result wrapping a [ResponseMap].
     */
    @Suppress("unused")
    fun runSimulationToResponseMap(modelInputs: ModelInputs): Result<ResponseMap> {
        val simulationRunResult = runSimulation(modelInputs)
        simulationRunResult.onFailure {
            return Result.failure(it)
        }
        return Result.success(
            simulationRunToResponseMap(
                modelInputs,
                simulationRunResult.getOrNull()!!
            )
        )
    }

    /**
     * Executes multiple simulation runs based on the provided list of model input data. Each request is
     * processed individually, and the result is recorded and returned as a map where the key is the
     * request and the value is the result of the simulation. If the input list is empty,
     * an exception is thrown. This default implementation runs all the requests sequentially based
     * on the order of the supplied list. The simulations are run independently with no common random
     * numbers and no caching.
     *
     * @param modelInputs the list of ModelInputs objects, each representing a simulation request to be executed.
     *                 The list must not be empty.
     * @return a map where each key is a RequestData object and each value is a Result wrapping a successful
     *         SimulationRun or an exception if the simulation for that request fails.
     * @throws IllegalArgumentException if the input list of requests is empty.
     */
    @Suppress("unused")
    fun runSimulations(modelInputs: List<ModelInputs>): Map<ModelInputs, Result<SimulationRun>> {
        require(modelInputs.isNotEmpty()) { "The supplied list of requests was empty!" }
        val resultMap = mutableMapOf<ModelInputs, Result<SimulationRun>>()
        for (request in modelInputs) {
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
            val myOriginalExpRunParams = model.extractRunParameters()
            val srp = model.extractRunParameters()
            srp.experimentName = if (expIdentifier != null) "${modelInputs.modelIdentifier}_Exp_$expIdentifier"
            else "${modelInputs.modelIdentifier}_Exp_${modelInputs.requestTime}"
            // ensure that the requested number of replications will be executed
            srp.numberOfReplications = modelInputs.numReplications
            logger.info { "SimulationService: Running simulation for model: ${modelInputs.modelIdentifier} experiment: ${srp.experimentName} " }
            val mySimulationRunner = SimulationRunner(model)
            //run the simulation to produce the simulation run results
            val simulationRun = mySimulationRunner.simulate(
                modelIdentifier = modelInputs.modelIdentifier,
                inputs = modelInputs.inputs,
                experimentRunParameters = srp)
            logger.info { "SimulationService: Completed simulation for model: ${modelInputs.modelIdentifier} experiment: ${srp.experimentName} " }
            // reset the model run parameters back to their original values
            model.changeRunParameters(myOriginalExpRunParams)
            return simulationRun
        }

    }

}

