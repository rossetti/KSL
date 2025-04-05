package ksl.simopt.cache

import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.Solution
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

    fun putAll(from: Map<out EvaluationRequest, Solution>) {
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
    val capacity: Int = Integer.MAX_VALUE
) : SolutionCacheIfc {

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

    private fun findEvictionCandidate(): InputMap {
        // find the first deterministically infeasible solution and return it
        for ((inputMap, solution) in map) {
            // remove the first infeasible
            if (!solution.isInputRangeFeasible() || !solution.isLinearConstraintFeasible() ||
                !solution.isFunctionalConstraintFeasible()
            ) {
                return inputMap
            }
            // or remove the first infinite or bad solution
            if (solution.penalizedObjFncValue.isNaN() || solution.penalizedObjFncValue.isInfinite()
                || solution.penalizedObjFncValue == Double.MAX_VALUE){
                return inputMap
            }
        }
        // if here then solutions were deterministically feasible
        TODO("Not yet implemented")

    }


}