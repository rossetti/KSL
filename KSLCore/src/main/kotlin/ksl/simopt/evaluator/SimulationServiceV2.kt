package ksl.simopt.evaluator

import ksl.controls.experiments.SimulationRun
import ksl.simopt.cache.SimulationRunCacheIfc
import ksl.simulation.Model
import ksl.simulation.ModelDescriptor
import ksl.simulation.ModelProviderIfc

abstract class SimulationServiceV2(
    val simulationRunCache: SimulationRunCacheIfc? = null
) : SimulationServiceIfcV2{

    abstract fun provideModel(modelIdentifier: String) : Model

    override fun modelDescriptors(): Result<List<ModelDescriptor>> {
        TODO("Not yet implemented")
    }

    override fun runSimulation(modelInputs: ModelInputs): Result<SimulationRun> {
        TODO("Not yet implemented")
    }

    override fun runSimulations(evaluationRequest: EvaluationRequest): Map<ModelInputs, Result<SimulationRun>> {
        TODO("Not yet implemented")
    }

    override fun simulate(evaluationRequest: EvaluationRequest): Map<ModelInputs, Result<ResponseMap>> {
        //This should call runSimulations(evaluationRequest: EvaluationRequest): Map<ModelInputs, Result<SimulationRun>>
        // and then translate the simulation runs to response maps.
        TODO("Not yet implemented")
    }
}