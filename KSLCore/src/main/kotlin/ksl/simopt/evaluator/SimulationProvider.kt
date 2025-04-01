package ksl.simopt.evaluator

import ksl.controls.experiments.SimulationRunner
import ksl.simulation.Model

/**
 *  This simulation provider will execute evaluation requests on the same model
 *  and collect the desired responses.
 */
class SimulationProvider(
    private val model: Model
) : SimulationProviderIfc {

    private val mySimulationRunner = SimulationRunner(model)

    override fun runSimulations(evaluationRequests: List<EvaluationRequest>): Map<EvaluationRequest, ResponseMap> {
        val results = mutableMapOf<EvaluationRequest, ResponseMap>()
        for (request in evaluationRequests) {
            //run the simulation and capture the simulation run
            val simulationRun = mySimulationRunner.simulate(request.inputMap, model.extractRunParameters())
            // extract the replication data for each simulation response
            val replicationData = simulationRun.results
            // make an empty response map to hold the estimated responses
            val responseMap = request.inputMap.problemDefinition.emptyResponseMap()
            // fill the response map
            for((name, _) in responseMap){
                require(replicationData.containsKey(name)){"The simulation responses did not contain the name $name"}
                // get the data from the simulation
                val data = replicationData[name]!!
                // compute the estimates from the replication data
                val estimatedResponse = EstimatedResponse(name, data)
                // place the estimate in the response map
                responseMap.add(estimatedResponse)
            }
            results[request] = responseMap
        }
        return results
    }

}