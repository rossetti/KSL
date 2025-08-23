package ksl.simopt.evaluator

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.controls.experiments.SimulationRun
import ksl.simopt.evaluator.SimulationProvider.Companion.simulationRunToResponseMap
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
    fun modelDescriptors(): List<ModelDescriptor>

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
    }

}

