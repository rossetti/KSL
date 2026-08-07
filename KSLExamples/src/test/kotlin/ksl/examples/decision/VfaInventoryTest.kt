package ksl.examples.decision

import ksl.modeling.decision.PolicyIfc
import ksl.modeling.nhpp.PiecewiseConstantRateFunction
import ksl.simulation.Model
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Can a modeler write a value-function policy against the design as it stands — their own
 *  action enumeration, their own `V̄` — and have it fit?
 *
 *  The answer splits, and the split is the finding. Evaluating a value function works with
 *  the interfaces that exist. Learning one does not, because the interface that exists for
 *  it is never called.
 */
class VfaInventoryTest {

    private fun run(
        rates: PiecewiseConstantRateFunction,
        rule: PolicyIfc,
        reps: Int = 30
    ): Pair<Model, Double> {
        val model = Model("VfaStudy")
        val inv = SsInventory(model, rateFunction = rates, name = "Inv")
        model.numberOfReplications = reps
        model.lengthOfReplication = 10_000.0
        model.lengthOfReplicationWarmUp = 2_000.0
        inv.review.policy = rule
        model.simulate()
        return model to costOf(model, "Inv").total
    }

    private fun bestStatic(rates: PiecewiseConstantRateFunction): Pair<String, Double> {
        var best: Triple<Int, Int, Double>? = null
        for (s in listOf(0, 2, 4, 6, 8, 10)) {
            for (q in listOf(4, 6, 8, 10, 14, 18)) {
                val c = run(rates, SsPolicy(s, s + q)).second
                if (best == null || c < best!!.third) best = Triple(s, q, c)
            }
        }
        val (bs, bq, bc) = best!!
        return "static s=$bs S=${bs + bq}" to bc
    }

    // ------------------------------------------------------------------ evaluation

    /**
     *  A VFA written entirely against today's interfaces: it enumerates 𝒳(s) from
     *  `feasibleBounds` and `isFeasible`, forms the post-decision state itself, and scores
     *  candidates with a `V̄` it brings. Nothing in the design had to change.
     *
     *  It should be competitive with a swept (s, S) rule — the newsvendor value term is the
     *  textbook justification for (s, S) structure, so a VFA built on it that lost badly
     *  would mean the enumeration or the context is not delivering what the rule asked for.
     */
    @Test
    fun aVfaCanBeWrittenAgainstTheExistingInterfaces() {
        for ((label, rates) in listOf(
            "stationary" to DemandRates.stationary,
            "seasonal" to DemandRates.seasonal
        )) {
            val myopic = NewsvendorVfaPolicy(amortizeSetup = false)
            val amortized = NewsvendorVfaPolicy(amortizeSetup = true)
            val myopicCost = run(rates, myopic).second
            val amortizedCost = run(rates, amortized).second
            val (staticLabel, staticCost) = bestStatic(rates)

            println()
            println("=== $label ===")
            println("  best swept (s,S)     %-22s %9.3f".format(staticLabel, staticCost))
            println("  VFA, myopic V̄        %-22s %9.3f  (%+.1f%%)"
                .format("no tuning", myopicCost, 100.0 * (staticCost - myopicCost) / staticCost))
            println("  VFA, one extra term  %-22s %9.3f  (%+.1f%%)"
                .format("no tuning", amortizedCost, 100.0 * (staticCost - amortizedCost) / staticCost))
            println("  enumeration: %,d feasibility checks".format(amortized.feasibilityChecks))

            // The architecture claim: a VFA runs and behaves sensibly. The QUALITY claim is
            // about V̄, not about the design — a myopic value term is known to lose to a
            // tuned (s,S) under a fixed ordering cost, and one continuation term closes it.
            assertTrue(
                amortizedCost < myopicCost,
                "the continuation term did not improve the value function on $label"
            )
            assertTrue(
                amortizedCost < 1.05 * staticCost,
                "the VFA with a continuation term was more than 5% worse than a swept (s,S) " +
                    "on $label — $amortizedCost vs $staticCost"
            )
        }
    }

    /**
     *  What enumeration costs when nothing enumerates for you. Every candidate is one
     *  `isFeasible` call, and §4.4.6.2 requires that to delegate to `prepare` — which
     *  builds a plan, reads each lever's current value, and sorts. Correct, and not free.
     */
    @Test
    fun enumerationIsAffordableHereAndTheReasonItMightNotBeIsMeasurable() {
        val vfa = NewsvendorVfaPolicy()
        val started = System.nanoTime()
        run(DemandRates.seasonal, vfa, reps = 5)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        val perCheck = if (vfa.feasibilityChecks > 0)
            (System.nanoTime() - started).toDouble() / vfa.feasibilityChecks else 0.0

        println()
        println("Enumeration, 5 replications:")
        println("  feasibility checks : %,d".format(vfa.feasibilityChecks))
        println("  candidates scored  : %,d".format(vfa.candidatesScored))
        println("  wall clock         : %,d ms".format(elapsedMs))
        println("  upper bound per check (includes the whole simulation): %.1f us".format(perCheck / 1000.0))
        assertTrue(vfa.feasibilityChecks > 100_000, "the probe did not enumerate enough to mean anything")
    }

    // ------------------------------------------------------------------ learning

    /**
     *  The other half of the question, and the one that fails.
     *
     *  `ManagedPolicyIfc` declares exactly the three hooks an adaptive rule needs —
     *  `beforeEpisode`, `onTransition`, `afterEpisode` — and a policy can implement them
     *  today. **Nothing calls them.** The epoch loop has no transition record to give
     *  (§8.1.3: capture is unimplemented) and no reward to put in one (the estimand is
     *  unimplemented), so a rule that wants to learn from its own experience has an
     *  interface to implement and no experience delivered to it.
     *
     *  This test asserts the gap. When capture and the estimand land, it should be
     *  rewritten to assert the opposite.
     */
    @Test
    fun theLearningHooksAreDeclaredButNeverCalled() {
        val probe = LearningProbePolicy(NewsvendorVfaPolicy())
        run(DemandRates.seasonal, probe, reps = 3)

        println()
        println("ManagedPolicyIfc hooks over ${probe.actionsTaken} decisions:")
        println("  beforeEpisode : ${probe.episodesStarted}")
        println("  onTransition  : ${probe.transitionsSeen}")
        println("  afterEpisode  : ${probe.episodesEnded}")
        println("  close         : ${probe.closes}")

        assertTrue(probe.actionsTaken > 1000, "the policy did not run")
        assertEquals(0, probe.episodesStarted,
            "beforeEpisode is now called — rewrite this test to assert the contract instead")
        assertEquals(0, probe.transitionsSeen,
            "onTransition is now called — a learning rule can be written; rewrite this test")
        assertEquals(0, probe.episodesEnded,
            "afterEpisode is now called — rewrite this test")
    }
}
