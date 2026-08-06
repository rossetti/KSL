package ksl.examples.decision

import ksl.modeling.nhpp.PiecewiseConstantRateFunction
import ksl.simulation.Model
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 *  §8.2.6 — the experiment §4.1.10 now demands of every worked example: does an adaptive
 *  rule beat the **best constant**, not merely the incumbent one?
 *
 *  Two demand processes, both non-homogeneous Poisson from `ksl.modeling.nhpp`, both with
 *  a mean rate of 1.0 over a repeating 100-unit cycle:
 *
 *    stationary  flat at 1.0
 *    seasonal    0.5 for 40, then 3.0 for 20, then 0.5 for 40
 *
 *  Same total demand, different timing. Everything else — costs, lead time, review period,
 *  streams — is identical, so any difference between the two is the non-stationarity and
 *  nothing else.
 *
 *  Both rules are swept over their own two parameters and compared best against best. A
 *  dynamic rule tuned against a static rule at its default settings would prove nothing.
 */
class NonStationarySsBenchmarkTest {

    private fun cost(rates: PiecewiseConstantRateFunction, rule: ksl.modeling.decision.PolicyIfc): Double {
        val model = Model("NonStationarySs")
        val inv = SsInventory(model, rateFunction = rates, name = "Inv")
        model.numberOfReplications = 30
        model.lengthOfReplication = 10_000.0
        model.lengthOfReplicationWarmUp = 2_000.0
        inv.review.policy = rule
        model.simulate()
        return costOf(model, "Inv").total
    }

    private data class Best(val label: String, val cost: Double)

    /** Sweep a static (s, S) rule, reporting the best and whether it is interior. */
    private fun bestStatic(rates: PiecewiseConstantRateFunction, label: String): Best {
        val sValues = listOf(0, 2, 4, 6, 8, 10)
        val qValues = listOf(4, 6, 8, 10, 14, 18)
        var best: Triple<Int, Int, Double>? = null
        println("  static (s, Q) sweep — $label:")
        for (sv in sValues) {
            val row = StringBuilder("    s=%3d : ".format(sv))
            for (q in qValues) {
                val c = cost(rates, SsPolicy(sv, sv + q))
                row.append("%8.2f ".format(c))
                if (best == null || c < best!!.third) best = Triple(sv, q, c)
            }
            println(row)
        }
        println("             " + qValues.joinToString(" ") { "    Q=%2d".format(it) })
        val (bs, bq, bc) = best!!
        val interior = bs != sValues.first() && bs != sValues.last() &&
            bq != qValues.first() && bq != qValues.last()
        println("    best static: s=%d, S=%d at %.3f%s".format(bs, bs + bq, bc,
            if (interior) "" else "   <-- ON THE GRID BOUNDARY"))
        require(interior) { "the static optimum is on the boundary (s=$bs, Q=$bq): widen the sweep" }
        return Best("static s=$bs S=${bs + bq}", bc)
    }

    /** Sweep the dynamic rule's two coefficients over the same model. */
    private fun bestDynamic(rates: PiecewiseConstantRateFunction, label: String): Best {
        val aValues = listOf(0.2, 0.4, 0.6, 0.8, 1.0, 1.2)
        val bValues = listOf(1.0, 1.5, 2.0, 2.5, 3.0, 3.5)
        var best: Triple<Double, Double, Double>? = null
        println("  dynamic (a, b) sweep — $label:")
        for (av in aValues) {
            val row = StringBuilder("    a=%.1f : ".format(av))
            for (bv in bValues) {
                val c = cost(rates, DynamicSsPolicy(av, bv))
                row.append("%8.2f ".format(c))
                if (best == null || c < best!!.third) best = Triple(av, bv, c)
            }
            println(row)
        }
        println("             " + bValues.joinToString(" ") { "   b=%.1f".format(it) })
        val (ba, bb, bc) = best!!
        val interior = ba != aValues.first() && ba != aValues.last() &&
            bb != bValues.first() && bb != bValues.last()
        println("    best dynamic: a=%.1f, b=%.1f at %.3f%s".format(ba, bb, bc,
            if (interior) "" else "   <-- ON THE GRID BOUNDARY"))
        require(interior) { "the dynamic optimum is on the boundary (a=$ba, b=$bb): widen the sweep" }
        return Best("dynamic a=%.1f b=%.1f".format(ba, bb), bc)
    }

    /**
     *  The control. With a flat rate the dynamic rule's `mu` is constant, so it reduces to
     *  a static rule and must NOT beat the best static one by any meaningful margin. If it
     *  does, the comparison below is measuring something other than adaptation.
     */
    @Test
    fun onStationaryDemandTheDynamicRuleHasNothingToAdaptTo() {
        println()
        println("=== STATIONARY demand (flat rate 1.0) ===")
        val stat = bestStatic(DemandRates.stationary, "stationary")
        val dyn = bestDynamic(DemandRates.stationary, "stationary")
        val gain = 100.0 * (stat.cost - dyn.cost) / stat.cost
        println("  adaptation gain: %+.1f%%  (expected: near zero)".format(gain))
        assertTrue(gain < 5.0, "the dynamic rule gained %.1f%% on stationary demand, where there is nothing to adapt to".format(gain))
    }

    /**
     *  The experiment. With the same mean demand arriving in a season, the dynamic rule
     *  should beat the best static rule — and if it does not, §4.1.10's requirement is not
     *  met by this model and the fault is in the cadence, the observation, or the claim.
     */
    @Test
    fun onSeasonalDemandTheDynamicRuleBeatsTheBestStaticRule() {
        println()
        println("=== SEASONAL demand (0.5 / 3.0 / 0.5, same mean) ===")
        val stat = bestStatic(DemandRates.seasonal, "seasonal")
        val dyn = bestDynamic(DemandRates.seasonal, "seasonal")
        val gain = 100.0 * (stat.cost - dyn.cost) / stat.cost
        println()
        println("  best static : %-24s %8.3f".format(stat.label, stat.cost))
        println("  best dynamic: %-24s %8.3f".format(dyn.label, dyn.cost))
        println("  adaptation gain: %+.1f%%".format(gain))
        println()
        assertTrue(
            dyn.cost < stat.cost,
            "the adaptive rule did not beat the best static rule on non-stationary demand. " +
                "Either the review cadence cannot track the season, the observation is wrong, " +
                "or the capability does not pay here — see §8.2.6."
        )
    }
}
