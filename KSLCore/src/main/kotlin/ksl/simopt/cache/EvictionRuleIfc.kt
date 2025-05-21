package ksl.simopt.cache

import ksl.simopt.evaluator.RequestData

//TODO needs revision
fun interface EvictionRuleIfc {

    fun findEvictionCandidate(solutionCache: SolutionCacheIfc): RequestData
}