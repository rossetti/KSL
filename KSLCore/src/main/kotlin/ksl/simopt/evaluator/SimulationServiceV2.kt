package ksl.simopt.evaluator

import ksl.controls.experiments.ExperimentRunParameters
import ksl.controls.experiments.SimulationRun
import ksl.simopt.cache.SimulationRunCacheIfc
import ksl.simulation.ModelProviderIfc

class SimulationServiceV2(
    val modelProvider: ModelProviderIfc,
    val simulationRunCache: SimulationRunCacheIfc? = null
) : SimulationServiceIfcV2{

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

    override fun runSimulation(modelInputs: ModelInputs): Result<SimulationRun> {
        TODO("Not yet implemented")
    }

    override fun runSimulations(evaluationRequest: EvaluationRequest): Map<ModelInputs, Result<SimulationRun>> {
        TODO("Not yet implemented")
    }

    override fun simulate(evaluationRequest: EvaluationRequest): Map<ModelInputs, Result<ResponseMap>> {
        TODO("Not yet implemented")
    }
}