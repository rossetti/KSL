package ksl.simopt.evaluator

import ksl.controls.experiments.SimulationRun


/**
 *  An interface that promises to run simulations on
 *  instances of input/output pairs. The keys of the map are
 *  evaluation requests for a specific number of replications for
 *  specific input variable values. The associated ResponseData
 *  represents the desired responses from the simulation. It should
 *  contain the replication averages for each desired response.
 */
interface SimulationServiceIfc : SimulationProviderIfc {

    fun runSimulationRequests(requests: List<RequestData>): List<SimulationRun>

    fun runSimulationRequest(request: RequestData): SimulationRun {
        return runSimulationRequests(listOf(request)).first()
    }

    //TODO what else can the service do
    // - indicate if model is registered
    // - return the response names for a model
    // - return the experiment run parameters for a model
    // - return the names of models that are registered
    // - return the input variable names for a model (variables and rv parameters)
    //
}