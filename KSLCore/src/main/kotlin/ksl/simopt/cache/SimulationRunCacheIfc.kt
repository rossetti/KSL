package ksl.simopt.cache

import ksl.controls.experiments.SimulationRun
import ksl.simopt.evaluator.ModelInputs
import ksl.utilities.io.ToJSONIfc

/**
 *  A simulation run cache should be designed to efficiently look up a simulation run
 *  based on a given set of input settings.
 */
interface SimulationRunCacheIfc : Map<ModelInputs, SimulationRun>, ToJSONIfc {

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
    fun put(modelInputs: ModelInputs, simulationRun: SimulationRun): SimulationRun?

    /**
     *  Looks up and removes the simulation run associated with the supplied request.
     *  Null is returned if there is no associated simulation run. It is important
     *  that implementors handle the reduced size relative to the cache.
     */
    fun remove(modelInputs: ModelInputs): SimulationRun?

    /**
     *  Places all input-solution pairs into the cache
     */
    @Suppress("unused")
    fun putAll(from: Map<out ModelInputs, SimulationRun>) {
        for ((input, simulationRun) in from) {
            put(input, simulationRun)
        }
    }

    /**
     *  Removes all items from the cache
     */
    fun clear()

    /**
     *  Retrieves the simulation runs associated with the requests
     */
    @Suppress("unused")
    fun retrieveSimulationRuns(requests: List<ModelInputs>): MutableMap<ModelInputs, SimulationRun> {
        val mm = mutableMapOf<ModelInputs, SimulationRun>()
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
    operator fun set(modelInputs: ModelInputs, simulationRun: SimulationRun) {
        put(modelInputs, simulationRun)
    }

    /**
     *  Retrieves the simulation runs in the cache as a list of simulation runs
     *  @return the list of simulation runs
     */
    fun simulationRuns(): List<SimulationRun>

    /**
     *  Partitions the simulation runs into groups that are determined by the model inputs
     *  that have the same input and response names. Any simulation runs that have model inputs
     *  with the same input names and response names are grouped together into a map.
     *  @return the returned map has keys that are the unique set of strings within the model
     *  input names and response names, with values representing the map of model inputs to related
     *  simulation runs.
     */
    @Suppress("unused")
    fun simulationRunsGroupedByModelInputNames(): Map<Set<String>, Map<ModelInputs, SimulationRun>> {
        val groupBy: Map<Set<String>, List<Map.Entry<ModelInputs, SimulationRun>>> =
            this.entries.groupBy { it.key.names() }
        // convert the grouped list of map entries to maps
        return groupBy.mapValues { toMap() }
    }
}

