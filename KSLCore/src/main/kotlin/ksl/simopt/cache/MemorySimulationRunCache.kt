package ksl.simopt.cache

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ksl.controls.experiments.SimulationRun
import ksl.simopt.evaluator.RequestData
import ksl.simopt.evaluator.SolutionData

/**
 *  A memory-based cache to hold simulation runs.  A simplified cache to avoid including
 *  more advanced caches in the dependency tree. This cache holds simulation runs
 *  in a map based on (RequestData, SimulationRun) pairs.  The cache is capacity
 *  constrained to the specified capacity.  The user can supply an eviction rule that
 *  will identify a simulation run to evict when the capacity is reached. If no eviction
 *  rule is supplied, then by default the algorithm removes the oldest simulation run.
 *  @constructor Creates an empty cache with the specified capacity.
 *  The default eviction rule is the oldest simulation run.
 *  @param capacity the maximum permitted size of the cache
 *  @param map the map to use as the cache.  The map must be mutable.
 *  @throws IllegalArgumentException if the capacity is less than 2.
 */
@Serializable
class MemorySimulationRunCache private constructor(
    override val capacity: Int,
    private val map: MutableMap<RequestData, SimulationRun>
) : SimulationRunCacheIfc {
    init {
        require(capacity >= 2) { "The cache's capacity must be >= 2" }
    }
    /**
     *  A memory-based cache to hold simulation runs.  A simplified cache to avoid including
     *  more advanced caches in the dependency tree. This cache holds simulation runs
     *  in a map based on (RequestData, SimulationRun) pairs.  The cache is capacity
     *  constrained to the specified capacity.  The user can supply an eviction rule that
     *  will identify a simulation run to evict when the capacity is reached. If no eviction
     *  rule is supplied, then by default the algorithm removes the oldest simulation run.
     *  @constructor Creates an empty cache with the specified capacity.
     *  The default eviction rule is the oldest simulation run.
     *  @param capacity the maximum permitted size of the cache
     *  @throws IllegalArgumentException if the capacity is less than 2.
     */
    constructor(capacity: Int = defaultCacheSize) : this(capacity, mutableMapOf())

    override var evictionRule: SimulationRunEvictionRuleIfc? = null

    override val entries: Set<Map.Entry<RequestData, SimulationRun>>
        get() = map.entries
    override val keys: Set<RequestData>
        get() = map.keys
    override val size: Int
        get() = map.size
    override val values: Collection<SimulationRun>
        get() = map.values

    override fun containsKey(key: RequestData): Boolean {
        return map.containsKey(key)
    }

    override fun containsValue(value: SimulationRun): Boolean {
        return map.containsValue(value)
    }

    override fun get(key: RequestData): SimulationRun? {
        return map[key]
    }

    override fun isEmpty(): Boolean {
        return map.isEmpty()
    }

    override fun remove(requestData: RequestData): SimulationRun? {
        return map.remove(requestData)
    }

    override fun simulationRuns(): List<SimulationRun> {
        return map.values.toList()
    }

    override fun put(requestData: RequestData, simulationRun: SimulationRun): SimulationRun? {
        require(validatePair(requestData, simulationRun)) { "The supplied request and simulation run are not valid." }
        if (size == capacity) {
            val itemToEvict = evictionRule?.findEvictionCandidate(this) ?: findEvictionCandidate()
            remove(itemToEvict)
        }
        require(size < capacity) { "The eviction of members did not work. No capacity for item in the cache." }
        return map.put(requestData, simulationRun)
    }

    /**
     * Validates whether a given RequestData instance is compatible with a specified SimulationRun instance.
     *
     * The compatibility criteria are:
     * 1. The model identifiers in RequestData and SimulationRun must match.
     * 2. All input names in RequestData must exist in the SimulationRun's inputs.
     * 3. All response names in RequestData must exist in the SimulationRun's results.
     *
     * @param requestData the RequestData instance containing the model identification, inputs, and response names to validate.
     * @param simulationRun the SimulationRun instance containing the inputs and results to validate against.
     * @return true if the RequestData is compatible with the SimulationRun, false otherwise.
     */
    fun validatePair(requestData: RequestData, simulationRun: SimulationRun): Boolean {
        // check if the model identifier matches
        if (requestData.modelIdentifier != simulationRun.modelIdentifier) return false
        // first check that the input names are in the simulation run
        for ((inputName, _) in requestData.inputs) {
            if (inputName !in simulationRun.inputs.keys) return false
        }
        // now check that the response names are in the simulation run
        for (responseName in requestData.responseNames) {
            if (responseName !in simulationRun.results.keys) return false
        }
        return true
    }

    /**
     *  By default, the eviction candidate will be the oldest request in the cache.
     */
    fun findEvictionCandidate(): RequestData {
        if (size == 1) {
            return keys.toList().first()
        }
        // this should be safe because there must be more than 2 items
        return map.keys.minBy { it.requestTime }
    }

    override fun toJson(): String {
        val format = Json {
            prettyPrint = true
            encodeDefaults = true
        }
        return format.encodeToString(this)
    }

    companion object {
        /**
         *  The default size for caches. By default, 1000.
         */
        var defaultCacheSize = 1000
            set(value) {
                require(value >= 2) { "The minimum cache size is 2" }
                field = value
            }
    }

}