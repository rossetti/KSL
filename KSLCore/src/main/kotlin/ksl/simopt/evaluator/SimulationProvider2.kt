package ksl.simopt.evaluator

import ksl.controls.experiments.SimulationRun
import ksl.simopt.cache.SimulationRunCacheIfc
import ksl.simulation.Model
import ksl.simulation.ModelProvider

/**
 *  This simulation provider will execute evaluation requests on the same model
 *  and collect the desired responses.  This provider runs the model's replications
 *  locally and sequentially in the same execution thread as the requests.
 *
 * @param modelProviders the models that are registered with this provider
 * @param simulationRunCache if supplied the cache will be used to store executed simulation runs.
 * @param useCachedSimulationRuns Indicates whether the provider should use cached simulation runs when responding
 * to requests. The default is false. If the simulation runs are not cached, this option has no effect.
 */
@Suppress("unused")
class SimulationProvider2(
    val modelProviders: Map<String, ModelProvider>,
    val simulationRunCache: SimulationRunCacheIfc? = null,
    var useCachedSimulationRuns: Boolean = false,
) {

//    private val mySimulationRunner = SimulationRunner(model)

    /**
     *  capture the original experiment run parameters so that they can
     *  be restored to the original after executing a simulation
     */
//    private val myOriginalExpRunParams: ExperimentRunParameters = model.extractRunParameters()

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

    fun runSimulation(request: RequestData): Result<SimulationRun> {
        TODO("Not implemented yet")
    }

    fun runSimulations(requests: List<RequestData>): Map<RequestData, ResponseMap> {
        val results = mutableMapOf<RequestData, ResponseMap>()
        for (request in requests) {
            //TODO validate request??
            if ((simulationRunCache != null) && useCachedSimulationRuns) {
                // use the cache instead of run the simulation
                respondFromCache(request, results)
            } else {
                executeSimulation(request, results)
            }
        }
        return results
    }

    fun isModelProvided(modelIdentifier: String): Boolean {
        return modelProviders.containsKey(modelIdentifier)
    }

    private fun respondFromCache(
        request: RequestData,
        results: MutableMap<RequestData, ResponseMap>
    ) {
        if ((simulationRunCache != null) ){
            // check if the request is in the cache
            if (simulationRunCache.containsKey(request)) {
                // check if it has the appropriate number of replications
                val requestedReplications = request.numReplications
                val simulationRun = simulationRunCache[request]!!
                if (requestedReplications <= simulationRun.numberOfReplications) {
                    captureResults(request, simulationRun, results)
                }
                return
            }
        }
        // if it is not in the cache, then execute the simulation
        executeSimulation(request, results)
    }

    private fun executeSimulation(
        request: RequestData,
        results: MutableMap<RequestData, ResponseMap>
    ) {
        executionCounter++
        // update experiment name on the model and number of replications
        model.experimentName = request.modelIdentifier + "_Exp_$executionCounter"
        model.numberOfReplications = request.numReplications
        if (request.experimentRunParameters != null) {
            // update the name and the number of replications on the supplied parameters
            request.experimentRunParameters.experimentName = model.experimentName
            request.experimentRunParameters.numberOfReplications = model.numberOfReplications
        }
        Model.logger.info { "SimulationProvider: Running simulation for experiment: ${model.experimentName} " }
        //run the simulation
        val simulationRun = mySimulationRunner.simulate(
            request.inputs,
            request.experimentRunParameters ?: model.extractRunParameters()
        )
        Model.logger.info { "SimulationProvider: Completed simulation for experiment: ${model.experimentName} " }
        // capture the simulation results
        captureResults(request, simulationRun, results)
        // add the SimulationRun to the simulation run cache
        simulationRunCache?.put(request, simulationRun)
        // reset the model run parameters back to their original values
        model.changeRunParameters(myOriginalExpRunParams)
    }

    private fun captureResults(
        request: RequestData,
        simulationRun: SimulationRun,
        results: MutableMap<RequestData, ResponseMap>
    ) {
        // extract the replication data for each simulation response
        val replicationData = simulationRun.results
        // if the request's response name set is empty then return all responses from the simulation run
        val responseNames = request.responseNames.ifEmpty {
            simulationRun.results.keys
        }
        // make an empty response map to hold the estimated responses
        val responseMap = ResponseMap(responseNames)
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
        // capture the responses for each request
        results[request] = responseMap
    }

}