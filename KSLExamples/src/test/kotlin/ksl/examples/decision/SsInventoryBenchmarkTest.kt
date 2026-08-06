package ksl.examples.decision

import ksl.modeling.decision.HoldCurrentPolicy
import ksl.modeling.decision.PolicyIfc
import ksl.simulation.Model
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 *  The (s, S) inventory exercise of §8.2. This is the canonical Markov decision process,
 *  so the design has to handle it well or the design has a problem.
 *
 *  Three things are being tested and only the first is about inventory:
 *
 *   1. Does an (s, S) rule expressed as a PolicyIfc actually work, and can a parameter
 *      sweep find the optimum?
 *   2. Does HoldCurrentPolicy — the Level-2 baseline the whole compatibility argument
 *      rests on — mean anything when the action is a transaction rather than a setting?
 *   3. Is the do-nothing arm §4.1.10 requires even expressible here?
 */
class SsInventoryBenchmarkTest {

    private fun run(rule: PolicyIfc, leverHasReader: Boolean = false): Model {
        val model = Model("SsInventoryStudy")
        val inv = SsInventory(model, leverHasReader = leverHasReader, name = "Inv")
        model.numberOfReplications = 30
        model.lengthOfReplication = 5_000.0
        model.lengthOfReplicationWarmUp = 1_000.0
        inv.review.policy = rule
        model.simulate()
        return model
    }

    private fun cost(rule: PolicyIfc, leverHasReader: Boolean = false) =
        costOf(run(rule, leverHasReader), "Inv")

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
     *  The prediction under test, and the reason this exercise was chosen.
     *
     *  The order quantity is a TRANSACTION, not a setting. There is no "current order
     *  quantity" to read, so the lever is write-only, so DecisionContext.currentAction
     *  has nothing to return, so HoldCurrentPolicy — the Level-2 compatibility baseline
     *  of §6.2 — cannot be evaluated at all.
     *
     *  If this test passes, the design's central guarantee is unavailable for the
     *  canonical MDP example. See §8.2.2.
     */
    @Test
    fun holdCurrentPolicyIsMeaninglessForATransactionalLever() {
        val e = runCatching { run(HoldCurrentPolicy) }.exceptionOrNull()
        println()
        println("HoldCurrentPolicy on an order-quantity lever: ${e?.let { it::class.simpleName + ": " + it.message } ?: "SUCCEEDED"}")
        assertTrue(
            e != null,
            "HoldCurrentPolicy ran on a transactional lever. If this now works, re-read §8.2.2 — " +
                "either a reader was added to the lever, or currentAction changed meaning."
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
     *  The only thing standing between an (s, S) model and that bug is that an order
     *  quantity has no obvious reader, so nobody supplies one. Supply one and the bug
     *  appears. Nothing in the design warns against it, because the design has one lever
     *  concept where it needs two (§8.2.3).
     */
    @Test
    fun givingATransactionalLeverAReaderSilentlyDropsRepeatedOrders() {
        val rule = SsPolicy(2, 10)
        val withoutReader = run(rule)
        val withReader = run(rule, leverHasReader = true)

        val ordersWithout = orderCount(withoutReader)
        val ordersWith = orderCount(withReader)
        val costWithout = costOf(withoutReader, "Inv").total
        val costWith = costOf(withReader, "Inv").total

        println()
        println("(s=2, S=10), identical models, identical rule:")
        println("  lever without a reader: %8.2f orders per replication, cost %8.2f"
            .format(ordersWithout, costWithout))
        println("  lever WITH a reader:    %8.2f orders per replication, cost %8.2f"
            .format(ordersWith, costWith))
        println("  orders lost to the no-op elision: %.2f (%.1f%%)"
            .format(ordersWithout - ordersWith, 100.0 * (ordersWithout - ordersWith) / ordersWithout))

        assertTrue(
            ordersWith < ordersWithout,
            "Expected the elision to drop repeated same-size orders. If this no longer " +
                "happens, LeverKind (§8.2.3) has been implemented and this test should assert " +
                "the opposite."
        )
    }
}
