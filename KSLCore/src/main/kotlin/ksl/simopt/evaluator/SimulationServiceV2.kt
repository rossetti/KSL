package ksl.simopt.evaluator

import ksl.controls.experiments.SimulationRun
import ksl.simopt.cache.SimulationRunCacheIfc
import ksl.simopt.evaluator.SimulationServiceIfcV2.Companion.executeSimulation
import ksl.simulation.Model
import ksl.simulation.ModelDescriptor

abstract class SimulationServiceV2(
    simulationRunCache: SimulationRunCacheIfc? = null
) : SimulationServiceIfcV2{

    protected abstract fun provideModel(modelIdentifier: String) : Model?

    /**
     *  The simulation provider for this service. This is used to execute simulations.
     *  The simulation provider is created by the service when it is instantiated.
     *  The provider is configured with the simulation run cache, if it exists.
     */
    protected val mySimulationProvider: SimulationProvider = SimulationProvider(Model(), simulationRunCache)

    val simulationRunCache: SimulationRunCacheIfc?
        get() = mySimulationProvider.simulationRunCache

    abstract fun modelIdentifiers() : Set<String>

    override fun modelDescriptors(): List<ModelDescriptor> {
        val list = mutableListOf<ModelDescriptor>()
        val ids = modelIdentifiers()
        for (id in ids) {
            val model = provideModel(id)
            if (model != null) {
                list.add(model.modelDescriptor())
            }
        }
        return list
    }

    override fun runSimulation(modelInputs: ModelInputs): Result<SimulationRun> {
        val model = provideModel(modelInputs.modelIdentifier)
        if (model == null) {
            val msg = "The SimulationService does not provide model ${modelInputs.modelIdentifier}\n" +
                    "model inputs: $modelInputs"
            SimulationServiceIfc.logger.error { msg }
            return Result.failure(ModelNotProvidedException(msg))
        }
        val simulationRun = executeSimulation(modelInputs, model)
        if (simulationRun.runErrorMsg.isNotEmpty()) {
            SimulationServiceIfc.logger.info { "SimulationService: Simulation for model: ${model.name} experiment: ${model.experimentName} had an error. " }
            SimulationServiceIfc.logger.info { "Error message: ${simulationRun.runErrorMsg} " }
            return Result.failure(SimulationRunException(simulationRun))
        }
        return Result.success(simulationRun)
    }

    override fun runSimulations(evaluationRequest: EvaluationRequest): Map<ModelInputs, Result<SimulationRun>> {
        val model = provideModel(evaluationRequest.modelIdentifier)
        if (model == null) {
            val msg = "The SimulationService does not provide model ${evaluationRequest.modelIdentifier}\n" +
                    "model inputs: $evaluationRequest"
            SimulationServiceIfc.logger.error { msg }
            val modelInputs = evaluationRequest.modelInputs.first()
            val failure = Result.failure<SimulationRun>(ModelNotProvidedException(msg))
            return mapOf(modelInputs to failure)
        }
        TODO("Not yet implemented")
    }

    override fun simulate(evaluationRequest: EvaluationRequest): Map<ModelInputs, Result<ResponseMap>> {
        //This should call runSimulations(evaluationRequest: EvaluationRequest): Map<ModelInputs, Result<SimulationRun>>
        // and then translate the simulation runs to response maps.
        TODO("Not yet implemented")
    }
}