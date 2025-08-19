package ksl.simopt.cache

import ksl.simopt.evaluator.ModelInputs

//TODO needs revision
fun interface EvictionRuleIfc {

    fun findEvictionCandidate(solutionCache: SolutionCacheIfc): ModelInputs
}