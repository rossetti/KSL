package ksl.simopt.cache

import ksl.simopt.evaluator.ModelInputs

fun interface SimulationRunEvictionRuleIfc {

    fun findEvictionCandidate(simulationRunCache: SimulationRunCacheIfc): ModelInputs
}