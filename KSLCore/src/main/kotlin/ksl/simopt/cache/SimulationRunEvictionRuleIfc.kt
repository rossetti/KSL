package ksl.simopt.cache

import ksl.simopt.evaluator.RequestData

fun interface SimulationRunEvictionRuleIfc {

    fun findEvictionCandidate(simulationRunCache: SimulationRunCacheIfc): RequestData
}