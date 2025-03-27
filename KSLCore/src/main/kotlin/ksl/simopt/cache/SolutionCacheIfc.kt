package ksl.simopt.cache

import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InputMap
import java.util.function.BiConsumer

//TODO needs revision

interface SolutionCacheIfc : MutableMap<InputMap, Solution>

//interface SolutionCacheIfc {
//    operator fun get(key: InputMap): Solution?
//
//    operator fun set(key: InputMap, value: Solution): Solution {
//        return put(key, value)
//    }
//
//    fun put(key: InputMap, value: Solution): Solution
//
//    fun clear()
//
//    fun keySet(): Set<InputMap>
//
//    fun values(): Collection<Solution>
//
//    fun isEmpty(): Boolean
//
//    fun containsKey(key: InputMap): Boolean
//
//    fun remove(key: InputMap): Solution?
//
//    fun forEach(action: BiConsumer<in InputMap, in Solution>)
//
//    fun size(): Int
//}