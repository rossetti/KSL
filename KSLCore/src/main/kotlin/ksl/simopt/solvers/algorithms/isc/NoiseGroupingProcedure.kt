package ksl.simopt.solvers.algorithms.isc

import ksl.utilities.distributions.Tukey
import kotlin.math.sqrt

/**
 *  A group of population members judged statistically indistinguishable in fitness.
 *
 *  @param members the members of the group, ordered best-first
 */
data class FitnessGroup(val members: List<SharedFitness>)

/**
 *  Noise-aware grouping for the ISC global phase (Algorithm 3). Because fitness is estimated with
 *  simulation noise, members whose shared-fitness differences fall within a studentized-range
 *  threshold `R = Q^{1−α_G} · S / √n̄` are treated as one group and later given a common
 *  (group-average) selection probability. Here `Q^{1−α_G}` is the studentized-range quantile
 *  ([Tukey.invCDF]), `S` is the pooled standard deviation of the (variance-scaled) fitness estimates,
 *  and `n̄` is the average replication count. At most [gm] groups are formed; once that many groups
 *  exist all remaining members join the last group.
 *
 *  @param alphaG the grouping significance level `α_G`; must be in (0,1)
 *  @param gm the maximum number of groups; must be at least 1
 */
class NoiseGroupingProcedure(
    var alphaG: Double = DEFAULT_ALPHA_G,
    var gm: Int = DEFAULT_GM
) {

    init {
        require(alphaG > 0.0 && alphaG < 1.0) { "alphaG must be in (0,1)" }
        require(gm >= 1) { "gm must be at least 1" }
    }

    /**
     *  Groups the supplied shared-fitness values (best-first) into at most [gm] noise-aware groups.
     */
    fun group(sharedFitness: List<SharedFitness>): List<FitnessGroup> {
        if (sharedFitness.isEmpty()) return emptyList()
        val ordered = sharedFitness.sortedBy { it.sharedFitness }
        if (ordered.size == 1) return listOf(FitnessGroup(ordered))
        val threshold = groupingThreshold(ordered)

        val groups = ArrayList<MutableList<SharedFitness>>()
        var current = ArrayList<SharedFitness>()
        current.add(ordered.first())
        var groupStart = ordered.first().sharedFitness
        for (idx in 1 until ordered.size) {
            val sf = ordered[idx]
            val startNewGroup = (sf.sharedFitness - groupStart) > threshold && groups.size < gm - 1
            if (startNewGroup) {
                groups.add(current)
                current = ArrayList()
                current.add(sf)
                groupStart = sf.sharedFitness
            } else {
                current.add(sf)
            }
        }
        groups.add(current)
        return groups.map { FitnessGroup(it) }
    }

    /**
     *  The studentized-range grouping threshold `R = Q^{1−α_G} · S / √n̄`.
     */
    fun groupingThreshold(sharedFitness: List<SharedFitness>): Double {
        val n = sharedFitness.size
        if (n < 2) return 0.0
        val avgVariance = sharedFitness.map { it.sharedVariance }.average()
        val s = sqrt(if (avgVariance.isNaN() || avgVariance < 0.0) 0.0 else avgVariance)
        if (s <= 0.0) return 0.0
        val avgCount = sharedFitness.map { it.solution.count }.average().coerceAtLeast(1.0)
        val df = (sharedFitness.sumOf { it.solution.count } - n).coerceAtLeast(1.0)
        val q = Tukey.invCDF(1.0 - alphaG, n.toDouble(), df)
        return q * s / sqrt(avgCount)
    }

    companion object {
        /** Default grouping significance level `α_G`. */
        const val DEFAULT_ALPHA_G: Double = 0.1

        /** Default maximum number of groups `g_m`. */
        const val DEFAULT_GM: Int = 3
    }
}
