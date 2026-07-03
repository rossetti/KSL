package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.evaluator.Solution
import ksl.utilities.distributions.StudentT
import kotlin.math.pow
import kotlin.math.sqrt

/**
 *  A global→local transition rule (§A.12): decides when the ISC global phase (the Niching GA) should
 *  stop so that the local COMPASS phase can begin. The Niching GA transitions when **any** of its
 *  configured rules fires.
 */
fun interface NgaTransitionRuleIfc {
    /** True if the global phase should transition to the local phase now. */
    fun shouldTransition(nga: NichingGeneticAlgorithmSolver): Boolean
}

/**
 *  Soft-budget transition rule (§A.12): transition once the global phase has consumed at least
 *  [replicationBudget] simulation replications.
 *
 *  @param replicationBudget the replication budget for the global phase; must be at least 1
 */
class BudgetRule(
    var replicationBudget: Int
) : NgaTransitionRuleIfc {
    init {
        require(replicationBudget >= 1) { "replicationBudget must be at least 1" }
    }

    override fun shouldTransition(nga: NichingGeneticAlgorithmSolver): Boolean =
        nga.numReplicationsRequested >= replicationBudget
}

/**
 *  Single-niche transition rule (§A.12): transition once only one niche remains, since further global
 *  exploration cannot separate additional basins.
 */
class SingleNicheRule : NgaTransitionRuleIfc {
    override fun shouldTransition(nga: NichingGeneticAlgorithmSolver): Boolean =
        nga.currentNiches.count <= 1
}

/**
 *  No-improvement transition rule (§A.12): transition once the best (incumbent) solution has not
 *  improved for [tG] consecutive generations.
 *
 *  @param tG the no-improvement generation threshold `T_G`; must be at least 1
 */
class ImprovementRule(
    var tG: Int = DEFAULT_TG
) : NgaTransitionRuleIfc {
    init {
        require(tG >= 1) { "tG must be at least 1" }
    }

    override fun shouldTransition(nga: NichingGeneticAlgorithmSolver): Boolean =
        nga.generationsSinceImprovement >= tG

    companion object {
        /** Default no-improvement threshold `T_G`. */
        const val DEFAULT_TG: Int = 3
    }
}

/**
 *  Dominance transition rule (§A.12.4): transition once one niche statistically **dominates** all of
 *  the others — its center's mean is better than every other niche center's mean by more than a
 *  Student-t margin. The per-comparison level uses the Bonferroni-style split `β = (1−α)^{1/(q−1)}`
 *  across the `q − 1` competing niches, and the margin is `t · SE` with `SE` the independent-sampling
 *  standard error of the difference of the two centers' objective estimates. With fewer than two
 *  niches the rule does not fire (the single-niche rule covers that case).
 *
 *  @param alpha the dominance error level `α`; must be in (0,1)
 */
class DominanceRule(
    var alpha: Double = DEFAULT_ALPHA
) : NgaTransitionRuleIfc {
    init {
        require(alpha > 0.0 && alpha < 1.0) { "alpha must be in (0,1)" }
    }

    override fun shouldTransition(nga: NichingGeneticAlgorithmSolver): Boolean {
        val centers = nga.currentNiches.niches.map { it.center }
        val q = centers.size
        if (q < 2) return false
        val best = centers.minByOrNull { it.estimatedObjFncValue } ?: return false
        val beta = (1.0 - alpha).pow(1.0 / (q - 1))
        for (other in centers) {
            if (other.inputMap == best.inputMap) continue
            val dof = (minOf(best.count, other.count) - 1.0).coerceAtLeast(1.0)
            val t = StudentT.invCDF(dof, beta)
            val se = standardErrorOfDifference(best, other)
            if (best.estimatedObjFncValue + t * se >= other.estimatedObjFncValue) {
                return false // does not dominate this competitor
            }
        }
        return true
    }

    private fun standardErrorOfDifference(a: Solution, b: Solution): Double {
        val va = a.estimatedObjFnc.variance.let { if (it.isNaN() || it <= 0.0) 0.0 else it }
        val vb = b.estimatedObjFnc.variance.let { if (it.isNaN() || it <= 0.0) 0.0 else it }
        return sqrt(va / a.count + vb / b.count)
    }

    companion object {
        /** Default dominance error level `α`. */
        const val DEFAULT_ALPHA: Double = 0.05
    }
}
