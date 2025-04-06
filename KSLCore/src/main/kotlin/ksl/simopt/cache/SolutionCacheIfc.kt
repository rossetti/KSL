package ksl.simopt.cache

import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.Solution
import ksl.simopt.evaluator.SolutionData
import ksl.simopt.problem.InputMap

//TODO needs revision
fun interface EvictionRuleIfc {

    fun findEvictionCandidate(solutionCache: SolutionCacheIfc): InputMap
}

interface SolutionCacheIfc : Map<InputMap, Solution> {

    var evictionRule: EvictionRuleIfc?

    fun put(inputMap: InputMap, solution: Solution): Solution?

    fun remove(inputMap: InputMap): Solution?

    fun put(request: EvaluationRequest, solution: Solution): Solution? {
        return put(request.inputMap, solution)
    }

    fun putAll(from: Map<out InputMap, Solution>) {
        for ((input, solution) in from) {
            put(input, solution)
        }
    }

    fun putAllRequests(from: Map<out EvaluationRequest, Solution>) {
        for ((input, solution) in from) {
            put(input, solution)
        }
    }

    fun clear() {
        for (key in keys) {
            remove(key)
        }
    }

    fun retrieveSolutions(requests: List<EvaluationRequest>): MutableMap<InputMap, Solution> {
        val mm = mutableMapOf<InputMap, Solution>()
        for (request in requests) {
            val solution = get(request.inputMap)
            if (solution != null) {
                mm[request.inputMap] = solution
            }
        }
        return mm
    }

    operator fun set(inputMap: InputMap, solution: Solution) {
        put(inputMap, solution)
    }

}

class MemorySolutionCache(
    val capacity: Int = defaultCacheSize
) : SolutionCacheIfc {
    init {
        require(capacity >= 2) {"The cache's capacity must be >= 2"}
    }

    private val map: MutableMap<InputMap, Solution> = mutableMapOf()

    override var evictionRule: EvictionRuleIfc? = null

    override val entries: Set<Map.Entry<InputMap, Solution>>
        get() = map.entries
    override val keys: Set<InputMap>
        get() = map.keys
    override val size: Int
        get() = map.size
    override val values: Collection<Solution>
        get() = map.values

    override fun containsKey(key: InputMap): Boolean {
        return map.containsKey(key)
    }

    override fun containsValue(value: Solution): Boolean {
        return map.containsValue(value)
    }

    override fun get(key: InputMap): Solution? {
        return map[key]
    }

    override fun isEmpty(): Boolean {
        return map.isEmpty()
    }

    override fun remove(inputMap: InputMap): Solution? {
        return map.remove(inputMap)
    }

    override fun put(inputMap: InputMap, solution: Solution): Solution? {
        if (size == capacity) {
            val itemToEvict = evictionRule?.findEvictionCandidate(this) ?: findEvictionCandidate()
            remove(itemToEvict)
        }
        require(size < capacity) { "The eviction of members did not work. No capacity for item in the cache." }
        return map.put(inputMap, solution)
    }

    /**
     *  By default, the eviction candidate will be the first deterministically infeasible solution,
     *  or the first solution with an infinite (or NaN) penalized objective function, or
     *  the first solution with an infinite (or NaN) objective function or the first solution (oldest) with the
     *  maximum penalized objective function.
     */
    fun findEvictionCandidate(): InputMap {
        if (size == 1) {
            return keys.toList().first()
        }
        for ((inputMap, solution) in map) {
            // remove the first deterministically infeasible solution and return it
            if (!solution.isInputRangeFeasible() || !solution.isLinearConstraintFeasible() ||
                !solution.isFunctionalConstraintFeasible()
            ) {
                return inputMap
            }
            // or remove the first infinite or bad constrained solution
            if (solution.penalizedObjFncValue.isNaN() || solution.penalizedObjFncValue.isInfinite()
                || solution.penalizedObjFncValue == Double.MAX_VALUE){
                return inputMap
            }
            // or remove the first infinite or bad unconstrained solution
            if (solution.estimatedObjFncValue.isNaN() || solution.estimatedObjFncValue.isInfinite()
                || solution.estimatedObjFncValue == Double.MAX_VALUE){
                return inputMap
            }
        }
        // if here then solutions were deterministically feasible, with non-problematic objective function values
        // find the oldest, largest solution to evict
        var largestSolValue = Double.MAX_VALUE
        var candidate: InputMap? = null
        for((inputMap, solution) in map) {
            val possibleMax = solution.penalizedObjFncValue
            if (possibleMax < largestSolValue){
                candidate = inputMap
                largestSolValue = possibleMax
            }
        }
        // this should be safe because there must be more than 2 items, and they must be less than MAX_VALUE.
        return candidate!!
    }

    /**
     *  Converts the data in the cache to a list containing instances
     *   of the SolutionData
     */
    fun toSolutionData() : List<SolutionData> {
        val list = mutableListOf<SolutionData>()
        for(solution in map.values){
            list.addAll(toSolutionData())
        }
        return list
    }

    companion object {
        /**
         *  The default size for caches. By default, 1000.
         */
        var defaultCacheSize = 1000
            set(value) {
                require(value >= 2) {"The minimum cache size is 2"}
                field = value
            }
    }

}