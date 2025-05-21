package ksl.simopt.evaluator

//typealias SimulationCases = Map<EvaluationRequest, ResponseMap>

/**
 *  A functional interface that promises to run simulations on
 *  instances of input/output pairs. The keys of the map are
 *  evaluation requests for a specific number of replications for
 *  specific input variable values. The associated ResponseMap
 *  represents the desired responses from the simulation. It should
 *  contain the replication averages for each desired response.
 */
fun interface SimulationProviderIfc {

    /**
     *  Promises to convert evaluation requests into responses.
     *
     * @param requests a list of evaluations
     * @return a map of the pair of evaluation requests and the responses from the simulation
     */
    fun runSimulations(requests: List<RequestData>): Map<RequestData, ResponseMap>

}