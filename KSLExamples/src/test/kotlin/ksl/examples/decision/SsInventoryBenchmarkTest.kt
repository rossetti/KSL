package ksl.examples.decision

import ksl.modeling.decision.DecisionElement
import ksl.modeling.decision.NeutralPolicy
import ksl.modeling.decision.PolicyIfc
import ksl.simulation.Model
import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  The (s, S) inventory exercise of §8.2. This is the canonical Markov decision process,
 *  so the design has to handle it well or the design has a problem.
 *
 *  Three things are being tested and only the first is about inventory:
 *
 *   1. Does an (s, S) rule expressed as a PolicyIfc actually work, and can a parameter
 *      sweep find the optimum?
 *   2. Does the Level-2 baseline the whole compatibility argument rests on mean anything
 *      when the action is a transaction rather than a setting? It did not — that is the
 *      finding of §8.2.2 — and §8.2.3's declared neutral is why it does now.
 *   3. What is left of the defect once the repair is in? The elision still drops orders
 *      under a lever DECLARED a setting, and the last test here measures exactly that.
 */
class SsInventoryBenchmarkTest {

    /**
     *  The two wirings of `SsInventory` are the same model.
     *
     *  `composedReview = true` puts the element inside a `PeriodicDecisionElement`; `false` declares
     *  it and attaches a caller. Both are kept in the example because the contrast teaches what the
     *  composite is — a convenience over the public `decide` door rather than another way in — and
     *  that claim is only worth making if the two produce the same run. Compared on the estimand and
     *  on the order count, which is what the rest of this class measures the model by.
     */
    @Test
    @DisplayName("The composed and hand-wired reviews are the same model")
    fun bothWiringsAgree() {
        fun wired(composed: Boolean): Model {
            val model = Model("Wiring-$composed")
            val inv = SsInventory(model, composedReview = composed, name = "Inv")
            model.numberOfReplications = 5
            model.lengthOfReplication = 1_000.0
            inv.review.policy = SsPolicy(2, 10)
            model.simulate()
            return model
        }

        val composed = wired(true)
        val assembled = wired(false)
        println()
        println("composed   : cost=${costOf(composed, "Inv").total}  orders=${orderCount(composed)}")
        println("hand-wired : cost=${costOf(assembled, "Inv").total}  orders=${orderCount(assembled)}")

        assertEquals(costOf(assembled, "Inv").total, costOf(composed, "Inv").total, 1e-9,
            "the composite must not change what the model does; if it does it is a second mechanism " +
                "rather than a convenience over the one the element already had")
        assertEquals(orderCount(assembled), orderCount(composed), 1e-9,
            "and the same rule must place the same orders at the same times")
    }


    private fun run(rule: PolicyIfc, declareOrderAsSetting: Boolean = false): Model {
        val model = Model("SsInventoryStudy")
        val inv = SsInventory(model, declareOrderAsSetting = declareOrderAsSetting, name = "Inv")
        model.numberOfReplications = 30
        model.lengthOfReplication = 5_000.0
        model.lengthOfReplicationWarmUp = 1_000.0
        inv.review.policy = rule
        model.simulate()
        return model
    }

    private fun cost(rule: PolicyIfc, declareOrderAsSetting: Boolean = false) =
        costOf(run(rule, declareOrderAsSetting), "Inv")

    private fun orderCount(model: Model) =
        model.counters.first { it.name == "Inv:OrderCount" }.acrossReplicationStatistic.average

    /**
     *  The exercise proper: sweep (s, S) and find the optimum. This is §4.1.4's
     *  parameterization story and B.5's "simulation optimization as policy search"
     *  reduced to a nested loop, with no machinery beyond swapping the policy.
     */
    @Test
    fun anSsSweepFindsAnOptimumAndBeatsDoingNothing() {
        val doNothing = cost(OrderNothingPolicy)
        println()
        println("do nothing: total = %8.2f  (setup %.2f, purchase %.2f, holding %.2f, shortage %.2f)"
            .format(doNothing.total, doNothing.orderSetup, doNothing.purchase,
                doNothing.holding, doNothing.shortage))
        println()
        println("(s, S) sweep — average cost per unit time:")

        // Swept as (s, Q) with S = s + Q, because that is how the two parameters act:
        // s sets the protection level against demand over the lead time plus the review
        // period, and Q trades setup cost against holding cost.
        val sValues = listOf(-2, 0, 2, 4, 6, 8)
        val qValues = listOf(2, 4, 6, 8, 10, 14)
        val grid = HashMap<Pair<Int, Int>, Double>()
        var best: Triple<Int, Int, Double>? = null
        for (sv in sValues) {
            val row = StringBuilder("  s=%3d : ".format(sv))
            for (q in qValues) {
                val c = cost(SsPolicy(sv, sv + q)).total
                grid[sv to q] = c
                row.append("%8.2f ".format(c))
                if (best == null || c < best!!.third) best = Triple(sv, q, c)
            }
            println(row)
        }
        println("           " + qValues.joinToString(" ") { "    Q=%2d".format(it) })
        println()
        println("best: s=%d, S=%d (Q=%d) at %.2f per unit time"
            .format(best!!.first, best!!.first + best!!.second, best!!.second, best!!.third))

        // A sweep whose winner sits on the edge of the grid has not bracketed anything.
        // This is the check that turns a table of numbers into evidence.
        val (bs, bq, _) = best!!
        assertTrue(
            bs != sValues.first() && bs != sValues.last() &&
                bq != qValues.first() && bq != qValues.last(),
            "the optimum is on the boundary of the grid (s=$bs, Q=$bq): widen the sweep"
        )

        assertTrue(best!!.third < doNothing.total, "no (s,S) rule beat ordering nothing")
    }

    /**
     *  The prediction this exercise was chosen to test, now inverted — which is what the
     *  earlier version of this test asked whoever fixed the design to do.
     *
     *  The order quantity is a TRANSACTION. There is no "current order quantity" to read,
     *  so `DecisionContext.currentAction` has nothing to return, so `HoldCurrentPolicy` —
     *  the Level-2 compatibility baseline of §6.2 — could not be evaluated at all, and the
     *  design's central guarantee was unavailable for the canonical MDP example (§8.2.2).
     *
     *  With a declared neutral (§8.2.3) the baseline is generic: `NeutralPolicy` orders the
     *  declared amount, which for this lever is zero, so it must agree exactly with the
     *  hand-written `OrderNothingPolicy` the exercise had to supply in its absence.
     */
    @Test
    fun theLevelTwoBaselineIsAvailableForATransactionalLever() {
        val generic = cost(NeutralPolicy)
        val handWritten = cost(OrderNothingPolicy)

        println()
        println("Level-2 baseline on an order-quantity lever:")
        println("  NeutralPolicy       total = %8.4f".format(generic.total))
        println("  OrderNothingPolicy  total = %8.4f  (hand-written, was the only option)"
            .format(handWritten.total))

        assertEquals(
            handWritten.total, generic.total, 1e-9,
            "NeutralPolicy did not reproduce the hand-written do-nothing arm, so the neutral " +
                "declared on the lever is not what the baseline writes (§8.2.3)"
        )
    }

    /**
     *  The second prediction, and the sharper one.
     *
     *  §4.4 plans every write as `from -> to` and DefaultActionBinding ELIDES a step whose
     *  target equals its source. That elision is required for a setting: §6.2's fine-grained
     *  Level-2 assertion depends on it. For a transaction it is silent data loss — two
     *  consecutive orders of the same size are one order, and the second is dropped.
     *
     *  What §8.2.3 changes is **which of the two a modeler ends up with**. Before it, the
     *  declaration could not say, `supportsCurrentValue = false` meant either "there is
     *  nothing to read" or "nobody supplied a reader", and §4.1.2.3 advised supplying one —
     *  so the design pushed a modeler toward the losing arm. Now the two arms are two
     *  spellings, the correct one is the shorter, and `LeverDescriptor.kind` carries the
     *  claim where a reviewer or a codec can see it.
     *
     *  It is worth being exact about the limit of the repair: **the bug is still reachable.**
     *  The library cannot know that an order is not a capacity. What it can do is refuse to
     *  infer, and that is what is asserted here — the loss still happens under the
     *  mis-declaration, and no longer happens under the natural one.
     */
    @Test
    fun theElisionStillDropsOrdersWhenATransactionIsDeclaredASetting() {
        val rule = SsPolicy(2, 10)
        val asTransaction = run(rule)
        val asSetting = run(rule, declareOrderAsSetting = true)

        val ordersCorrect = orderCount(asTransaction)
        val ordersWrong = orderCount(asSetting)
        val costCorrect = costOf(asTransaction, "Inv").total
        val costWrong = costOf(asSetting, "Inv").total

        println()
        println("(s=2, S=10), identical models, identical rule, two declarations:")
        println("  Neutral.Value(0.0)   TRANSACTION : %8.2f orders per replication, cost %8.2f"
            .format(ordersCorrect, costCorrect))
        println("  Neutral.Current {…}  SETTING     : %8.2f orders per replication, cost %8.2f"
            .format(ordersWrong, costWrong))
        println("  orders lost to the no-op elision under the mis-declaration: %.2f (%.1f%%)"
            .format(ordersCorrect - ordersWrong, 100.0 * (ordersCorrect - ordersWrong) / ordersCorrect))
        println("  the kind is now in the descriptor, so the claim is auditable:")
        for ((label, m) in listOf("TRANSACTION arm" to asTransaction, "SETTING arm" to asSetting)) {
            val d = m.getModelElement("Inv:Review") as DecisionElement
            println("    %-16s %s".format(label, d.descriptor().levers.map { "${it.name}=${it.kind}" }))
        }

        assertTrue(
            ordersWrong < ordersCorrect,
            "Expected the elision to drop repeated same-size orders when the lever is " +
                "DECLARED a setting. If this no longer happens, the elision has stopped " +
                "applying to settings, which §6.2 depends on."
        )
    }
}
