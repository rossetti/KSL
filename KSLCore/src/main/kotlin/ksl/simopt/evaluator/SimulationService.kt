package ksl.simopt.evaluator

import ksl.controls.experiments.SimulationRun
import ksl.simopt.cache.SimulationRunCacheIfc
import ksl.simopt.evaluator.SimulationProvider.Companion.captureResultFromSimulationRun
import ksl.simopt.evaluator.SimulationProvider.Companion.executeSimulation
import ksl.simulation.Model
import ksl.simulation.ModelDescriptor

abstract class SimulationService(
    val simulationRunCache: SimulationRunCacheIfc? = null
) : SimulationServiceIfc {

    protected abstract fun provideModel(modelIdentifier: String): Model?

    abstract fun modelIdentifiers(): Set<String>

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
        // With respect to CRN, this is okay because this is a single run.
        // CRN does not make sense in the context of a single run.
        val originalExpRunParams = model.extractRunParameters()
        val simulationRun = executeSimulation(modelInputs, model)
        model.changeRunParameters(originalExpRunParams)
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
        val originalExpRunParams = model.extractRunParameters()
        // The evaluation request has options for caching and CRN that need to be handled
        if (evaluationRequest.crnOption || !evaluationRequest.cachingAllowed || (simulationRunCache == null)) {
            // CRN should not permit cache retrieval. There is no way to ensure that the simulation runs
            // in the cache used CRN and saving dependent samples in the cache is problematic.
            // If there is no caching allowed or there isn't a cache, we just do the simulations.
            if (evaluationRequest.crnOption) {
                model.resetStartStreamOption = true
            }
            val results = simulateWithoutCache(evaluationRequest.modelInputs, model)
            model.changeRunParameters(originalExpRunParams)
            return results
        }
        // There is a cache and we are permitted to use it.
        val results = mutableMapOf<ModelInputs, Result<SimulationRun>>()
        for (modelInputs in evaluationRequest.modelInputs) {
            var simulationRun = useCachedSimulationRun(modelInputs)
            if (simulationRun == null) {
                simulationRun = executeSimulation(modelInputs, model)
            }
            results[modelInputs] = if (simulationRun.runErrorMsg.isEmpty()) {
                Result.success(simulationRun)
            } else {
                SimulationServiceIfc.logger.info { "SimulationService: Simulation for model: ${model.name} experiment: ${model.experimentName} had an error. " }
                SimulationServiceIfc.logger.info { "Error message: ${simulationRun.runErrorMsg} " }
                Result.failure(SimulationRunException(simulationRun))
            }
        }
        model.changeRunParameters(originalExpRunParams)
        return results
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

    private fun simulateWithoutCache(
        modelInputs: List<ModelInputs>,
        model: Model
    ): Map<ModelInputs, Result<SimulationRun>> {
        val results = mutableMapOf<ModelInputs, Result<SimulationRun>>()
        for (modelInputs in modelInputs) {
            val simulationRun = executeSimulation(modelInputs, model)
            results[modelInputs] = if (simulationRun.runErrorMsg.isEmpty()) {
                Result.success(simulationRun)
            } else {
                SimulationServiceIfc.logger.info { "SimulationService: Simulation for model: ${model.name} experiment: ${model.experimentName} had an error. " }
                SimulationServiceIfc.logger.info { "Error message: ${simulationRun.runErrorMsg} " }
                Result.failure(SimulationRunException(simulationRun))
            }
        }
        return results
    }

    override fun simulate(evaluationRequest: EvaluationRequest): Map<ModelInputs, Result<ResponseMap>> {
        // translate the simulation runs to response maps.
        val simulations = runSimulations(evaluationRequest)
        val allResults = mutableMapOf<ModelInputs, Result<ResponseMap>>()
        for ((modelInputs, simulationRunResult) in simulations) {
            simulationRunResult.onFailure {
                allResults[modelInputs] = Result.failure(it)
            }.onSuccess {
                allResults[modelInputs] = captureResultFromSimulationRun(modelInputs, it)
            }
        }
        return allResults
    }
}