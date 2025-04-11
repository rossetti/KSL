package ksl.simopt.cache

import ksl.simopt.problem.InputMap

//TODO needs revision
fun interface EvictionRuleIfc {

    fun findEvictionCandidate(solutionCache: SolutionCacheIfc): InputMap
}