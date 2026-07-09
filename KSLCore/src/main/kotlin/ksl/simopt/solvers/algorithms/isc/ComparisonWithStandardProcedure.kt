package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InputMap
import kotlin.math.max
import kotlin.math.pow

/**
 *  The outcome of a [ComparisonWithStandardProcedure] run.
 *
 *  @param standardIsBest true if the standard system survived as the best (no alternative was found
 *  better than the standard by the indifference amount) — for COMPASS this means the center is
 *  locally optimal
 *  @param winner the selected system: the standard when [standardIsBest] is true, otherwise the
 *  alternative judged better than the standard
 *  @param finalStandard the standard system with all of the replications it accumulated during the
 *  test (equal to [winner] when [standardIsBest] is true). Callers should write this back so the
 *  standard's extra observations are not discarded when an alternative wins.
 *  @param observations the total number of replications each system carried at termination, keyed by
 *  input point (the standard included)
 */
data class StandardComparisonResult(
    val standardIsBest: Boolean,
    val winner: Solution,
    val finalStandard: Solution,
    val observations: Map<InputMap, Int>
)

/**
 *  Kim's (2005) fully-sequential *comparison with a standard* procedure, used by COMPASS as the
 *  local-optimality test: the current center `x*` is the **standard**, its neighbors are the
 *  **alternatives**, and the procedure decides — with a controlled error probability — whether any
 *  neighbor is better than `x*` by more than the indifference amount [delta].
 *
 *  Each system starts with [n0] replications. For each alternative `i` the per-replication
 *  difference `d_j = alt_i_j - standard_j` (minimization, so a *negative* mean means the alternative
 *  is better) is accumulated into `Z_i(r) = Σ d_j`. A triangular continuation region with
 *  half-width `a_i(r) = max(0, h² S²_i / (2δ) − (δ/2) r)` brackets the walk, where `S²_i` is the
 *  first-stage sample variance of the differences and `h² = 2 η (n0 − 1)`. At each stage:
 *
 *  - `Z_i(r) < −a_i(r)` ⇒ alternative `i` is significantly better than the standard ⇒ the standard
 *    loses and `i` is the winner;
 *  - `Z_i(r) > +a_i(r)` ⇒ alternative `i` is significantly worse ⇒ eliminate `i`;
 *  - otherwise keep sampling `i`.
 *
 *  When every alternative has been eliminated the standard is declared best. Bonferroni splits the
 *  error across the `k` alternatives: `β = α / k` and
 *  `η = ½ ((2β)^(−2/(n0−1)) − 1)` (the closed form for the single-constant case, `c = 1`).
 *
 *  This procedure requires a positive [delta]; with `delta == 0` the boundary degenerates and the
 *  walk never terminates, which is why COMPASS uses it only when `δ_L > 0` (see the ISC degraded-mode
 *  documentation).
 *
 *  @param alpha the overall error probability; must be in (0,1)
 *  @param delta the indifference-zone parameter `δ_L`; must be positive
 *  @param n0 the first-stage sample size per system; must be at least 2
 *  @param c the comparison-constant flag from Kim (2005); only `c = 1` is supported
 *  @param maxReplications a hard cap on replications per system, guaranteeing termination even if the
 *  boundary has not forced a decision; must be at least [n0]
 */
class ComparisonWithStandardProcedure(
    var alpha: Double = DEFAULT_ALPHA,
    var delta: Double,
    var n0: Int = DEFAULT_N0,
    var c: Int = 1,
    var maxReplications: Int = DEFAULT_MAX_REPLICATIONS
) {

    init {
        require(alpha > 0.0 && alpha < 1.0) { "alpha must be in (0,1)" }
        require(delta > 0.0) { "delta must be positive (the comparison-with-a-standard test degenerates at delta = 0)" }
        require(n0 >= 2) { "n0 must be at least 2" }
        require(c == 1) { "only c = 1 (single comparison constant) is supported" }
        require(maxReplications >= n0) { "maxReplications must be at least n0" }
    }

    /**
     *  The Kim (2005) constant `η` for the given per-alternative error [beta] (the closed form for
     *  `c = 1`): `η = ½ ((2β)^(−2/(n0−1)) − 1)`.
     */
    fun eta(beta: Double): Double {
        require(beta > 0.0 && beta < 1.0) { "beta must be in (0,1)" }
        return 0.5 * ((2.0 * beta).pow(-2.0 / (n0 - 1)) - 1.0)
    }

    /**
     *  Runs the fully-sequential comparison. [standard] and each of the [alternatives] must already
     *  carry at least [n0] replications. [sampleOneMore] is invoked to obtain one additional
     *  replication for a given point (the returned [Solution] is for that point with one more
     *  observation, *not* the merged total) — the procedure merges it internally via the supplied
     *  [merge] function so that callers control how accumulation happens.
     *
     *  @param standard the standard system (COMPASS center `x*`)
     *  @param alternatives the competing systems (the neighbors of `x*`); may be empty, in which case
     *  the standard is trivially best
     *  @param sampleOneMore obtains one more replication for the given point
     *  @param merge merges an accumulated solution with a fresh single-replication observation
     *  @return the [StandardComparisonResult]
     */
    fun run(
        standard: Solution,
        alternatives: List<Solution>,
        sampleOneMore: (InputMap) -> Solution,
        merge: (Solution, Solution) -> Solution
    ): StandardComparisonResult {
        if (alternatives.isEmpty()) {
            return StandardComparisonResult(true, standard, standard, mapOf(standard.inputMap to standard.count.toInt()))
        }
        val k = alternatives.size
        val beta = alpha / k
        val etaVal = eta(beta)
        val hSquared = 2.0 * etaVal * (n0 - 1)

        var std = standard
        val alts = alternatives.toMutableList()
        // First-stage variance of the difference S^2_i (Kim 2005; ISC appendix eq. 9). COMPASS
        // evaluates neighbors independently (no CRN), so the difference variance is the SUM of the
        // two per-system variances, not their max.
        val sVar = DoubleArray(k) { i -> differenceVariance(std, alts[i]) }
        val active = (0 until k).toMutableSet()
        var winnerAlt: Solution? = null

        while (active.isNotEmpty() && winnerAlt == null) {
            val iterator = active.iterator()
            var advanced = false
            while (iterator.hasNext()) {
                val i = iterator.next()
                val r = minOf(std.count, alts[i].count).toInt()
                val z = (alts[i].penalizedObjFncValue - std.penalizedObjFncValue) * r // Z_i(r) ~ r * mean diff
                val a = max(0.0, hSquared * sVar[i] / (2.0 * delta) - (delta / 2.0) * r)
                if (z < -a) {
                    winnerAlt = alts[i] // alternative significantly better than the standard
                    break
                } else if (z > a) {
                    iterator.remove() // alternative significantly worse: eliminate
                } else if (r >= maxReplications) {
                    // Forced decision at the cap: keep the standard unless the alternative is better.
                    if (alts[i].penalizedObjFncValue < std.penalizedObjFncValue) {
                        winnerAlt = alts[i]
                        break
                    } else {
                        iterator.remove()
                    }
                }
            }
            if (winnerAlt != null) break
            // Take one more replication on the standard and each still-active alternative.
            if (active.isNotEmpty()) {
                std = merge(std, sampleOneMore(std.inputMap))
                for (i in active) {
                    alts[i] = merge(alts[i], sampleOneMore(alts[i].inputMap))
                }
                advanced = true
            }
            if (!advanced) break
        }

        val observations = LinkedHashMap<InputMap, Int>()
        observations[std.inputMap] = std.count.toInt()
        for (i in 0 until k) observations[alts[i].inputMap] = alts[i].count.toInt()

        return if (winnerAlt == null) {
            StandardComparisonResult(true, std, std, observations)
        } else {
            StandardComparisonResult(false, winnerAlt, std, observations)
        }
    }

    /**
     *  The first-stage sample variance of the difference between two systems, `S^2_i` in Kim (2005)
     *  and the ISC appendix (eq. 9). Under independent sampling (COMPASS does not use CRN for
     *  neighbors) `Var(a - b) = Var(a) + Var(b)`.
     */
    internal fun differenceVariance(a: Solution, b: Solution): Double = varianceOf(a) + varianceOf(b)

    private fun varianceOf(s: Solution): Double {
        val v = s.estimatedObjFnc.variance
        return if (v.isNaN() || v <= 0.0) 1.0 else v
    }

    companion object {
        /** Default overall error probability. */
        const val DEFAULT_ALPHA: Double = 0.05

        /** Default first-stage sample size. */
        const val DEFAULT_N0: Int = 10

        /** Default hard cap on replications per system. */
        const val DEFAULT_MAX_REPLICATIONS: Int = 200
    }
}
