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

    //TODO generalize cases to a data class that has information that will allow validation
    // of the requests prior to processing them

    /**
     *  Promises to convert evaluation requests into responses.
     *  The values in the response map will be updated by the simulation.
     *
     * @param cases a map of (input, output) pairs for evaluation
     */
    fun runSimulations(cases: Map<EvaluationRequest, ResponseMap>)

}