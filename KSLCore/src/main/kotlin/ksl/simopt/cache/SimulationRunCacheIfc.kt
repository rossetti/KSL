package ksl.simopt.cache

import ksl.controls.experiments.SimulationRun
import ksl.simopt.evaluator.ModelInputs
import ksl.utilities.io.ToJSONIfc
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.api.toDataFrame

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

    /**
     *  Cached simulation runs are associated with instances of [ModelInputs].  The equality of model inputs
     *  is determined by the model identifier, the names (and values) of the inputs, and the names of the desired responses.
     *  For the purposes of this function, we assume that the simulation runs are grouped (partitioned) by
     *  those model inputs that have the same input names and same requested responses. See [simulationRunsGroupedByModelInputNames()].
     *  This function returns the inputs and replication responses within a map of maps. The outer map
     *  represents the common input names and requested responses, and the inner map the inputs and responses.
     *  You can think of the inner map as holding the columns of the simulation run inputs and responses for each
     *  replication within the run. This "tabular" representation is useful for analysis of input to output relationships.
     *  The model input values will be repeated for each replication. The response values represent the observed
     *  replication average for the response.  The outer grouping is necessary because not every requested simulation
     *  run will have the same input names and/or requested responses. The grouping ensures those that have common
     *  names (inputs/response) will be within the associated map.
     *  @return a map of maps. The key to the outer map is the set of input and response names. The inner map is a
     *  data map of the inputs and the replication responses.
     */
    fun toMappedDataGroupedByModelInputNames(): Map<Set<String>, Map<String, List<Double>>> {
        val map = mutableMapOf<Set<String>, Map<String, MutableList<Double>>>()
        val srMap = simulationRunsGroupedByModelInputNames()
        for ((names, simResults) in srMap) {
            val tmp = mutableMapOf<String, MutableList<Double>>()
            tmp["simRunId"] = mutableListOf()
            var i = 1.0
            for ((modelInputs, simulationRun) in simResults) {
                tmp["simRunId"]!!.addAll(MutableList(simulationRun.numberOfReplications){i})
                for ((n, _) in modelInputs.inputs) {
                    if (!tmp.containsKey(n)) {
                        tmp[n] = mutableListOf()
                    }
                }
                for ((n, v) in modelInputs.inputs) {
                    val list = MutableList(simulationRun.numberOfReplications) { v }
                    tmp[n]!!.addAll(list)
                }
                val rNames = modelInputs.responseNames.ifEmpty { simulationRun.results.keys }
                for ((rn, responseData) in simulationRun.results) {
                    if (!tmp.containsKey(rn)) {
                        if ((rn in rNames) || (rn == "repNumbers")) {
                            tmp[rn] = mutableListOf()
                        }
                    }
                    if ((rn in rNames) || (rn == "repNumbers")) {
                        tmp[rn]!!.addAll(responseData.toList())
                    }
                }
                i++
            }
            map[names] = tmp
        }
        return map
    }

    /**
     *  Translates the maps returned by [toMappedDataGroupedByModelInputNames()] into data frames
     *  holding the input-response data for each replication.
     *  @return a map of data frames. The key to the outer map is the set of input and response names. The
     *  dataframe holds the data of inputs and responses.
     */
    fun toDataFramesGroupedByModelInputNames(): Map<Set<String>, AnyFrame> {
        return toMappedDataGroupedByModelInputNames().mapValues { it.value.toDataFrame() }
    }
}

