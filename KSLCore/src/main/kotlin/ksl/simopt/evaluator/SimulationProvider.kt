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

        for (request in evaluationRequests) {
            //TODO validate the inputs from the request as valid for the model

            //TODO assign the inputs to the model's inputs

            //TODO setup the experiment to run

            //TODO run the simulation and capture the simulation run

            //TODO extract the desired responses from the simulation responses

            //TODO fill the response map
        }
        TODO("Not yet implemented")
    }

}