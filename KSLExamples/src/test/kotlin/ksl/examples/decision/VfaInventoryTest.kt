package ksl.examples.decision

import ksl.modeling.decision.ActionSet
import ksl.modeling.decision.DecisionContext
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
 *  The answer splits, and the split is the finding. Evaluating a value function works with the
 *  interfaces that exist. Learning one did not, because the interface that exists for it was
 *  never called — and M1 steps 7b and 7c have since fixed that, so the second half of this test
 *  now asserts the contract rather than its absence.
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
            println("  (enumeration, filtering and argmin now come from LookaheadPolicy)")

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
     *  The action set as an object. Before §4.4.6.5 a rule could ask for bounds and test
     *  membership but had no way to ask **how many** actions there were — so it could not
     *  tell a set it could walk from one it could not.
     */
    @Test
    fun theActionSetReportsItsOwnSize() {
        var minSize = Long.MAX_VALUE
        var maxSize = 0L
        var sawNull = false

        val prober = object : PolicyIfc {
            override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
                val n = ctx.actions.size
                if (n == null) sawNull = true else {
                    minSize = minOf(minSize, n)
                    maxSize = maxOf(maxSize, n)
                    // Enumerating must agree with the count it reports.
                    check(ctx.actions.asSequence().count().toLong() == n) {
                        "asSequence yielded a different number of actions than size reported"
                    }
                }
                return DoubleArray(ctx.leverNames.size)
            }
        }
        run(DemandRates.seasonal, prober, reps = 2)

        println()
        println("Action set size over the run: min $minSize, max $maxSize, ever unenumerable: $sawNull")
        // This lever has no state-dependent bounds, so the size is constant here — the
        // varying case is asserted on the shipment depot, where 𝒳(s) genuinely moves.
        assertTrue(!sawNull, "an integer lever inside the ceiling should report a size")
        assertTrue(maxSize <= ActionSet.ENUMERATION_CEILING, "size should be null above the ceiling")
    }

    // ------------------------------------------------------------------ learning

    /**
     *  The other half of the question, and the one that used to fail.
     *
     *  `ManagedPolicyIfc` declares the three hooks an adaptive rule needs — `beforeEpisode`,
     *  `onTransition`, `afterEpisode` — and for a long time **nothing called them**. The epoch loop
     *  had no transition to give and no reward to put in one, so a rule that wanted to learn from
     *  its own experience had an interface to implement and no experience delivered to it. This
     *  test asserted that gap, and said in its failure message that it should be inverted when the
     *  plumbing landed. M1 steps 7b and 7c landed it (§7.1.1).
     *
     *  What is asserted now is the contract rather than its absence, and the counts are the
     *  contract: **one episode per replication** (§4.6.3), so `beforeEpisode` and `afterEpisode`
     *  fire once each per replication and never more; and one transition per completed interval,
     *  which is fewer than the number of decisions because the first interval of each episode has
     *  no predecessor to close and the last may have no duration (§4.8.3).
     */
    @Test
    fun theLearningHooksAreCalledWithOneEpisodePerReplication() {
        val reps = 3
        val probe = LearningProbePolicy(NewsvendorVfaPolicy())
        run(DemandRates.seasonal, probe, reps = reps)

        println()
        println("ManagedPolicyIfc hooks over ${probe.actionsTaken} decisions in $reps replications:")
        println("  beforeEpisode : ${probe.episodesStarted}")
        println("  onTransition  : ${probe.transitionsSeen}")
        println("  afterEpisode  : ${probe.episodesEnded}")
        println("  close         : ${probe.closes}")
        println("  beforeExpt    : ${probe.experimentsStarted}")
        println("  afterExpt     : ${probe.experimentsEnded}")

        assertTrue(probe.actionsTaken > 1000, "the policy did not run")
        assertEquals(reps, probe.episodesStarted,
            "one episode per replication (§4.6.3), so exactly $reps beginnings")
        assertEquals(reps, probe.episodesEnded,
            "and exactly $reps endings — an episode that ends early must not also be ended again " +
                "by replicationEnded()")
        // §4.7. The element closes what the element OPENED, and it did not open this policy.
        assertEquals(0, probe.closes,
            "the element must not close a policy it was handed. It used to close at " +
                "afterExperiment(), which left a second model.simulate() running against " +
                "released resources — the per-experiment pair below is what replaced it")
        assertEquals(1, probe.experimentsStarted, "beforeExperiment() is per experiment")
        assertEquals(1, probe.experimentsEnded, "and afterExperiment() pairs with it")
        assertEquals(0, probe.actionsAfterClose, "and nothing decided after a close")

        assertTrue(probe.transitionsSeen > 0,
            "a rule can now learn from its own experience; this is the assertion the earlier " +
                "version of this test was written to be replaced by")
        assertTrue(probe.transitionsSeen < probe.actionsTaken,
            "there must be fewer transitions than decisions: the first interval of each episode " +
                "has no predecessor to close (§4.10.2 step 4)")
        assertTrue(probe.transitionsSeen >= probe.actionsTaken - 2 * reps,
            "but only a little fewer — ${probe.actionsTaken - probe.transitionsSeen} were " +
                "discarded across $reps replications. The gap is bounded by two per episode: " +
                "§4.10.2.1 gives attempts = decisions + 1 and discards = 1 + [warm-up] + " +
                "[horizon on an epoch boundary], so gap = discards - 1 <= 2. This run has both " +
                "terms, so three transitions are discarded per episode and the gap is exactly two")
    }
}
