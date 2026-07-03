package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InputMap
import kotlin.math.max
import kotlin.math.sqrt

/**
 *  A *batch* simulation-allocation rule: it distributes an additional replication budget across the
 *  whole set of competing solutions at once (rather than one solution at a time). This is required by
 *  allocation schemes such as OCBA whose per-system effort depends on the means and variances of all
 *  the competitors. [CompassSolver] uses this batch path when its allocation rule implements this
 *  interface, sizing the budget from the rule's per-solution schedule and then letting the rule
 *  reallocate it.
 */
fun interface BatchAllocationRuleIfc {

    /**
     *  Returns the number of *additional* replications to give each solution so that, together with
     *  the replications they already carry, [additionalBudget] new replications are distributed across
     *  [solutions] according to the rule. Solutions receiving none may be omitted from the result.
     *
     *  @param solutions the competing solutions (each carrying its current mean, variance, and count)
     *  @param additionalBudget the number of additional replications to distribute
     *  @return a map from each solution's input point to its additional replication count (>= 0)
     */
    fun allocate(solutions: List<Solution>, additionalBudget: Int): Map<InputMap, Int>
}

/**
 *  The Optimal Computing Budget Allocation (OCBA) simulation-allocation rule (ISC appendix
 *  Algorithm 7). Given the current sample means and variances of the competing solutions, OCBA
 *  distributes a replication budget to maximize the probability of correct selection: more effort goes
 *  to systems that are *close to the current best* (small mean gap) and *noisy* (large variance), with
 *  the best system itself sampled in proportion to the aggregate competition. For minimization, with
 *  best `b = argmin X̄_i`, the allocation satisfies
 *
 *  - `N_i / N_j = (σ_i / δ_{b,i})² / (σ_j / δ_{b,j})²` for non-best `i, j`, where `δ_{b,i} = X̄_i − X̄_b`,
 *  - `N_b = σ_b · sqrt( Σ_{i≠b} (N_i / σ_i)² )`.
 *
 *  [allocate] turns these ratios into target replication counts over the pooled budget
 *  `(current total) + additionalBudget` and returns each solution's shortfall to its target.
 *
 *  Because OCBA is inherently a multi-system rule, the primary entry point is the batch [allocate]
 *  (via [BatchAllocationRuleIfc]); [CompassSolver] uses it automatically. For drop-in compatibility
 *  with the per-solution [SimulationAllocationRuleIfc], [additionalReplications] delegates to an
 *  internal [FixedScheduleSAR] floor (the slightly-super-logarithmic schedule), which also sizes the
 *  batch budget when OCBA drives a COMPASS run.
 *
 *  @param initialReplications the floor `n0` on replications per solution; defaults to
 *  [FixedScheduleSAR.DEFAULT_INITIAL_REPLICATIONS]
 *  @param epsilon the positive growth-exponent offset for the floor schedule; defaults to
 *  [FixedScheduleSAR.DEFAULT_EPSILON]
 *  @param varianceFloor a positive lower bound applied to each variance so zero-variance estimates do
 *  not collapse the ratios; must be > 0
 *  @param deltaFloor a positive lower bound applied to each mean gap `|X̄_i − X̄_b|` so near-ties do not
 *  produce infinite weights; must be > 0
 */
class OcbaSAR(
    val initialReplications: Int = FixedScheduleSAR.DEFAULT_INITIAL_REPLICATIONS,
    val epsilon: Double = FixedScheduleSAR.DEFAULT_EPSILON,
    val varianceFloor: Double = DEFAULT_VARIANCE_FLOOR,
    val deltaFloor: Double = DEFAULT_DELTA_FLOOR
) : SimulationAllocationRuleIfc, BatchAllocationRuleIfc {

    init {
        require(varianceFloor > 0.0) { "varianceFloor must be positive" }
        require(deltaFloor > 0.0) { "deltaFloor must be positive" }
    }

    private val floor = FixedScheduleSAR(initialReplications, epsilon)

    /** The per-solution floor schedule (delegates to [FixedScheduleSAR]); also sizes the OCBA budget. */
    override fun additionalReplications(solution: Solution, iteration: Int): Int =
        floor.additionalReplications(solution, iteration)

    override fun allocate(solutions: List<Solution>, additionalBudget: Int): Map<InputMap, Int> {
        if (solutions.isEmpty() || additionalBudget <= 0) return emptyMap()
        if (solutions.size == 1) {
            return mapOf(solutions.first().inputMap to additionalBudget)
        }
        val k = solutions.size
        val means = DoubleArray(k) { solutions[it].estimatedObjFncValue }
        val sd = DoubleArray(k) { sqrt(max(varianceFloor, varianceOf(solutions[it]))) }
        val counts = DoubleArray(k) { solutions[it].count }

        var bestIdx = 0
        for (i in 1 until k) if (means[i] < means[bestIdx]) bestIdx = i

        val weights = DoubleArray(k)
        var sumOfSquares = 0.0
        for (i in 0 until k) {
            if (i == bestIdx) continue
            val delta = max(deltaFloor, kotlin.math.abs(means[i] - means[bestIdx]))
            val w = (sd[i] / delta) * (sd[i] / delta)
            weights[i] = w
            val ratio = w / sd[i]
            sumOfSquares += ratio * ratio
        }
        weights[bestIdx] = sd[bestIdx] * sqrt(sumOfSquares)

        val totalWeight = weights.sum()
        if (totalWeight <= 0.0) {
            // Degenerate (all means equal and floored): distribute the budget evenly.
            return evenAllocation(solutions, additionalBudget)
        }
        val pooled = counts.sum() + additionalBudget
        val result = LinkedHashMap<InputMap, Int>()
        for (i in 0 until k) {
            val target = weights[i] / totalWeight * pooled
            val additional = max(0, Math.round(target).toInt() - counts[i].toInt())
            if (additional > 0) result[solutions[i].inputMap] = additional
        }
        return result
    }

    private fun evenAllocation(solutions: List<Solution>, additionalBudget: Int): Map<InputMap, Int> {
        val per = additionalBudget / solutions.size
        if (per <= 0) return emptyMap()
        return solutions.associate { it.inputMap to per }
    }

    private fun varianceOf(s: Solution): Double {
        val v = s.estimatedObjFnc.variance
        return if (v.isNaN() || v <= 0.0) 0.0 else v
    }

    companion object {
        /** Default positive lower bound on a solution's variance. */
        const val DEFAULT_VARIANCE_FLOOR: Double = 1.0e-6

        /** Default positive lower bound on a mean gap to the best. */
        const val DEFAULT_DELTA_FLOOR: Double = 1.0e-6
    }
}
