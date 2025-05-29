package ksl.simopt.evaluator

import ksl.simopt.cache.SimulationRunCacheIfc

//typealias SimulationCases = Map<EvaluationRequest, ResponseMap>

/**
 *  An interface that promises to run simulations on
 *  instances of input/output pairs. The keys of the map are
 *  evaluation requests for a specific number of replications for
 *  specific input variable values. The associated ResponseMap
 *  represents the desired responses from the simulation. It should
 *  contain the replication averages for each desired response.
 */
interface SimulationProviderIfc {

    /**
     *  Indicates if the simulation provider should cache simulation runs.
     */
    var cacheSimulationRuns: Boolean

    /**
     *  Use to hold executed simulation runs.
     */
    val simulationRunCache: SimulationRunCacheIfc

    /**
     *  Promises to convert evaluation requests into responses.
     *
     * @param requests a list of evaluations
     * @return a map of the pair of evaluation requests and the responses from the simulation
     */
    fun runSimulations(requests: List<RequestData>): Map<RequestData, ResponseMap>


}