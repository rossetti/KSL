package ksl.simopt.solvers.algorithms.isc

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import ksl.utilities.Interval
import ksl.utilities.distributions.StudentT
import ksl.utilities.statistic.Rinott
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 *  The ISC clean-up (ranking & selection) phase. After the global and local phases produce a set of
 *  candidate local optima, clean-up (1) *screens* them with a subset-selection rule, (2) *selects*
 *  the best survivor — with a correct-selection guarantee when an indifference zone is supplied — and
 *  (3) *reports* a confidence interval for the chosen system's objective.
 *
 *  **Indifference zone `δ_C` (graceful degradation).** ISC places `δ_C` in the denominator of the
 *  Rinott sample-size formula, so `δ_C = 0` cannot drive an indifference-zone selection. Following the
 *  ISC degraded-mode convention:
 *
 *  - **`deltaC > 0` — full ISC.** [select] runs the Rinott two-stage indifference-zone procedure
 *    (`N_C = ⌈(h·S/δ_C)²⌉`, floored at the first-stage size) and [estimate] reports `ḡ(x_B) ± δ_C`
 *    with the correct-selection guarantee.
 *  - **`deltaC = 0` — degraded.** [select] takes no additional samples and returns the screened
 *    sample-best (**no** correct-selection guarantee), and [estimate] returns an ordinary
 *    Student-t confidence interval on the chosen system's mean. The IZ guarantees are intentionally
 *    dropped; a positive `δ_C` is required to obtain them.
 *
 *  **Why this is computed from summary statistics (not `MultipleComparisonAnalyzer`).** A [Solution]
 *  carries only summary statistics (average, variance, replication count), not the raw per-replication
 *  observations that `MultipleComparisonAnalyzer` requires (it needs equal-length raw arrays to form
 *  paired differences). Rather than retain raw replications — which would require changing `Solution`
 *  or `EstimatedResponse` outside this package — the subset-selection screen and the intervals are
 *  computed in-package from the public summary statistics using the same Nelson et al. (2001)
 *  formulation (independent-sampling standard error of the difference). The Rinott constant itself is
 *  reused from [Rinott].
 *
 *  **Runaway safety valve (`maxReplicationsPerSystem`).** The Rinott second-stage size grows as
 *  `(h·S/δ_C)²`, so a survivor with high objective variance relative to a small `δ_C` can demand an
 *  enormous number of replications (millions for a noisy problem with a tight indifference zone). To
 *  keep a single clean-up phase bounded, the per-survivor second-stage size is capped at
 *  [maxReplicationsPerSystem]. When the cap binds, clean-up samples the survivor up to the cap and
 *  proceeds — the correct-selection guarantee then holds only in a best-effort sense (the indifference
 *  zone effectively achieved is larger than the requested `δ_C`). A warning is logged whenever the cap
 *  binds. Raise the cap (or `δ_C`) to restore the exact guarantee.
 *
 *  @param problemDefinition the problem whose objective is being screened
 *  @param deltaC the clean-up indifference zone `δ_C`; must be >= 0 (`0.0` selects degraded mode)
 *  @param oneMinusAlphaC the target confidence/correct-selection level; must be in (0,1)
 *  @param maxReplicationsPerSystem the cap on the Rinott second-stage sample size per survivor; must be
 *  >= 1. Defaults to [DEFAULT_MAX_REPLICATIONS_PER_SYSTEM]. Bounds clean-up cost when the variance-to-
 *  indifference-zone ratio is large; the guarantee is best-effort once the cap binds.
 */
class CleanUpProcedure(
    val problemDefinition: ProblemDefinition,
    var deltaC: Double,
    var oneMinusAlphaC: Double = DEFAULT_CONFIDENCE,
    var maxReplicationsPerSystem: Int = DEFAULT_MAX_REPLICATIONS_PER_SYSTEM
) {

    init {
        require(deltaC >= 0.0) { "deltaC must be >= 0" }
        require(oneMinusAlphaC > 0.0 && oneMinusAlphaC < 1.0) { "oneMinusAlphaC must be in (0,1)" }
        require(maxReplicationsPerSystem >= 1) { "maxReplicationsPerSystem must be >= 1" }
    }

    /** True when the procedure runs with full indifference-zone guarantees (`deltaC > 0`). */
    val hasIndifferenceZone: Boolean
        get() = deltaC > 0.0

    private fun mean(s: Solution): Double = s.estimatedObjFncValue

    private fun variance(s: Solution): Double {
        val v = s.estimatedObjFnc.variance
        return if (v.isNaN() || v <= 0.0) 0.0 else v
    }

    private fun count(s: Solution): Double = s.count

    /**
     *  Subset-selection screen for the minimum (Nelson et al. 2001): returns the candidates that could
     *  plausibly be the best at confidence [oneMinusAlphaC]. A candidate `i` is retained when its mean
     *  is no larger than `ḡ_j + t · SE(i,j)` for every other candidate `j`, where `SE(i,j)` is the
     *  independent-sampling standard error of the difference. Valid at any `δ_C` (screening does not
     *  use the indifference zone). With one or zero candidates the input is returned unchanged.
     */
    fun screen(candidates: List<Solution>): List<Solution> {
        if (candidates.size <= 1) return candidates
        val k = candidates.size
        val alpha = 1.0 - oneMinusAlphaC
        val p = 1.0 - alpha / (k - 1.0)
        val retained = ArrayList<Solution>(k)
        for (i in candidates.indices) {
            val si = candidates[i]
            var keep = true
            for (j in candidates.indices) {
                if (i == j) continue
                val sj = candidates[j]
                val dof = max(1.0, minOf(count(si), count(sj)) - 1.0)
                val t = StudentT.invCDF(dof, p)
                val se = standardErrorOfDifference(si, sj)
                if (mean(si) > mean(sj) + t * se) {
                    keep = false
                    break
                }
            }
            if (keep) retained.add(si)
        }
        // Guard against an empty result (e.g., ties at the boundary): keep the sample-best.
        return retained.ifEmpty { listOf(candidates.minByOrNull { mean(it) }!!) }
    }

    private fun standardErrorOfDifference(a: Solution, b: Solution): Double {
        val va = variance(a)
        val vb = variance(b)
        val na = count(a)
        val nb = count(b)
        return sqrt(va / na + vb / nb)
    }

    /**
     *  Selects the best survivor. With `deltaC > 0` runs the Rinott two-stage indifference-zone
     *  procedure: each survivor is sampled up to `N_i = max(n0, ⌈(h·S_i/δ_C)²⌉)` replications (with
     *  `n0` the smallest current replication count among the survivors and `h` the Rinott constant),
     *  then the smallest second-stage sample mean is selected. The per-survivor second-stage size is
     *  clamped to [maxReplicationsPerSystem]; when the clamp binds the procedure logs a warning and the
     *  correct-selection guarantee becomes best-effort. With `deltaC == 0` (degraded) no extra sampling
     *  is done and the survivor with the smallest mean is returned.
     *
     *  @param survivors the screened candidates (must be non-empty)
     *  @param sampleMore obtains a [Solution] carrying the requested number of additional replications
     *  for the given point; the result is merged into the running total via [mergeSolutions]
     *  @return the selected best solution (with any second-stage replications merged in)
     */
    fun select(survivors: List<Solution>, sampleMore: (InputMap, Int) -> Solution): Solution {
        require(survivors.isNotEmpty()) { "There must be at least one survivor to select from" }
        if (survivors.size == 1) return survivors.first()
        if (!hasIndifferenceZone) {
            return survivors.minByOrNull { mean(it) }!!
        }
        val k = survivors.size
        val n0 = survivors.minOf { count(it) }
        val dof = n0.toInt() - 1
        if (dof < MIN_RINOTT_DOF) {
            // Not enough first-stage replications for the Rinott constant: degrade to the sample-best.
            return survivors.minByOrNull { mean(it) }!!
        }
        val h = Rinott().rinottConstant(k, oneMinusAlphaC, dof)
        if (h.isNaN()) {
            return survivors.minByOrNull { mean(it) }!!
        }
        val cap = maxReplicationsPerSystem.toDouble()
        var cappedSystems = 0
        var maxRequested = 0.0
        val finalized = survivors.map { s ->
            val sd = sqrt(variance(s))
            val rinott = ceil((h * sd / deltaC) * (h * sd / deltaC))
            if (rinott > cap) {
                cappedSystems++
                maxRequested = max(maxRequested, rinott)
            }
            val required = max(n0, rinott.coerceAtMost(cap))
            val additional = (required - count(s)).toInt()
            if (additional > 0) mergeSolutions(s, sampleMore(s.inputMap, additional)) else s
        }
        if (cappedSystems > 0) {
            logger.warn {
                "CleanUpProcedure: Rinott second-stage size capped at maxReplicationsPerSystem=" +
                        "$maxReplicationsPerSystem for $cappedSystems of $k survivor(s) " +
                        "(largest requested ~${maxRequested.toLong()}); the correct-selection guarantee " +
                        "is best-effort. Increase maxReplicationsPerSystem or deltaC to restore it."
            }
        }
        return finalized.minByOrNull { mean(it) }!!
    }

    /**
     *  The reported confidence interval for the selected [best] system's objective. With `deltaC > 0`
     *  this is the indifference-zone interval `ḡ(x_B) ± δ_C` (the correct-selection precision target).
     *  With `deltaC == 0` it is an ordinary two-sided Student-t confidence interval on the mean at
     *  level [oneMinusAlphaC].
     */
    fun estimate(best: Solution): Interval {
        val avg = mean(best)
        if (hasIndifferenceZone) {
            return Interval(avg - deltaC, avg + deltaC)
        }
        val n = count(best)
        val sd = sqrt(variance(best))
        if (n < 2.0 || sd <= 0.0) return Interval(avg, avg)
        val dof = n - 1.0
        val p = 1.0 - (1.0 - oneMinusAlphaC) / 2.0
        val t = StudentT.invCDF(dof, p)
        val hw = t * sd / sqrt(n)
        return Interval(avg - hw, avg + hw)
    }

    companion object {
        /** Default confidence / correct-selection level. */
        const val DEFAULT_CONFIDENCE: Double = 0.95

        /** Minimum first-stage degrees of freedom required to compute the Rinott constant. */
        const val MIN_RINOTT_DOF: Int = 4

        /**
         *  Default cap on the Rinott second-stage sample size per survivor. Chosen large enough to
         *  preserve the exact indifference-zone guarantee for moderate variance-to-`δ_C` ratios (a
         *  standard-deviation-to-`δ_C` ratio up to roughly 50 with the usual Rinott constant) while
         *  bounding the pathological case where a noisy objective and a tight `δ_C` would otherwise
         *  demand millions of replications.
         */
        const val DEFAULT_MAX_REPLICATIONS_PER_SYSTEM: Int = 20_000

        val logger: KLogger = KotlinLogging.logger {}
    }
}
