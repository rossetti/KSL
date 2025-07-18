package ksl.simopt.cache

import ksl.controls.experiments.SimulationRun
import ksl.simopt.evaluator.RequestData
import ksl.utilities.io.ToJSONIfc

/**
 *  A simulation run cache should be designed to efficiently look up a simulation run
 *  based on a given set of input settings.
 */
interface SimulationRunCacheIfc : Map<RequestData, SimulationRun>, ToJSONIfc {

    /**
     *  The maximum permitted size of the cache
     */
    val capacity: Int

    /**
     *  A rule to govern which solution should be evicted when the cache capacity is met.
     */
    var evictionRule: SimulationRunEvictionRuleIfc?

    /**
     *  Places the simulation run into the cache. It is important that implementors
     *  ensure that the input names and response names associated with the request are consistent with the
     *  input names and response names of the simulation run.
     */
    fun put(requestData: RequestData, simulationRun: SimulationRun): SimulationRun?

    /**
     *  Looks up and removes the simulation run associated with the supplied request.
     *  Null is returned if there is no associated simulation run. It is important
     *  that implementors handle the reduced size relative to the cache.
     */
    fun remove(requestData: RequestData): SimulationRun?

    /**
     *  Places all input-solution pairs into the cache
     */
    @Suppress("unused")
    fun putAll(from: Map<out RequestData, SimulationRun>) {
        for ((input, simulationRun) in from) {
            put(input, simulationRun)
        }
    }

    /**
     *  Removes all items from the cache
     */
    fun clear() {
        for (key in keys) {
            remove(key)
        }
    }

    /**
     *  Retrieves the simulation runs associated with the requests
     */
    @Suppress("unused")
    fun retrieveSimulationRuns(requests: List<RequestData>): MutableMap<RequestData, SimulationRun> {
        val mm = mutableMapOf<RequestData, SimulationRun>()
        for (request in requests) {
            val simulationRun = get(request)
            if (simulationRun != null) {
                mm[request] = simulationRun
            }
        }
        return mm
    }

    /**
     *  Allows use of bracket operator for setting values
     */
    operator fun set(requestData: RequestData, simulationRun: SimulationRun) {
        put(requestData, simulationRun)
    }

    /**
     *  Retrieves the simulation runs in the cache as a list of simulation runs
     *  @return the list of simulation runs
     */
    fun simulationRuns() : List<SimulationRun>

}

