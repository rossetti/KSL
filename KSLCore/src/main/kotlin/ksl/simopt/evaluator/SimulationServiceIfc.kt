package ksl.simopt.evaluator


/**
 *  A functional interface that promises to run simulations on
 *  instances of input/output pairs. The keys of the map are
 *  evaluation requests for a specific number of replications for
 *  specific input variable values. The associated ResponseData
 *  represents the desired responses from the simulation. It should
 *  contain the replication averages for each desired response.
 */
fun interface SimulationServiceIfc {

    fun runSimulations(requests: List<RequestData>): Map<RequestData, ResponseData>

}