package ksl.examples.decision

import ksl.modeling.decision.ActionValidationException
import ksl.modeling.decision.DecisionContext
import ksl.modeling.decision.PolicyIfc
import ksl.simulation.Model
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  The exercise §4.4.6 was written for: a model whose feasible action set genuinely depends
 *  on state. Every arm runs the identical depot; only the declaration and the policy's
 *  access to the feasible set change.
 *
 *  Four questions:
 *
 *   1. Can the model declare its own conservation law, or must every policy re-derive it?
 *   2. What happens when a policy gets the re-derivation wrong?
 *   3. Does `ctx.isFeasible` ever disagree with `prepare`?
 *   4. Does the CFA that §4.4.6 enables actually beat the PFA that worked before it?
 */
class ShipmentAllocationTest {

    private fun run(stateDependent: Boolean, rule: (ShipmentDepot) -> PolicyIfc): Pair<Model, ShipmentDepot> {
        val model = Model("ShipmentStudy")
        val depot = ShipmentDepot(model, stateDependentDeclaration = stateDependent, name = "Depot")
        model.numberOfReplications = 30
        model.lengthOfReplication = 5_000.0
        model.lengthOfReplicationWarmUp = 1_000.0
        depot.allocation.policy = rule(depot)
        model.simulate()
        return model to depot
    }

    private fun cost(m: Model, d: ShipmentDepot) = shipmentCost(m, "Depot", d.shortageRates)

    // ---------------------------------------------------------------- question 1 & 2

    /**
     *  Without §4.4.6 the element cannot state "ship no more than is on hand", so a policy
     *  that over-ships is NOT rejected — the model silently absorbs it. That absorption is
     *  the model defending itself against its own decision element, which §4.3.3 says
     *  should never be necessary.
     */
    @Test
    fun withoutTheFeasibleSetTheModelMustDefendItself() {
        // A rule that respects the truck cap but not the stock — the exact mistake the old
        // declaration cannot catch, because the stock is not part of any declared constraint.
        val greedyIgnoringStock = object : PolicyIfc {
            override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
                val n = ctx.leverNames.size
                var remaining = 100.0                      // the truck, and nothing about stock
                val plan = DoubleArray(n)
                for (i in 0 until n) {
                    val give = minOf(observation[i], remaining)
                    plan[i] = Math.rint(give)
                    remaining -= plan[i]
                }
                return plan
            }
        }

        val (_, oldDepot) = run(stateDependent = false) { greedyIgnoringStock }
        println()
        println("OLD declaration, rule ignores stock:")
        println("  over-shipments the model had to absorb: ${oldDepot.overShipmentsAbsorbed}")
        assertTrue(
            oldDepot.overShipmentsAbsorbed > 0,
            "expected the old declaration to let an over-shipping rule through"
        )

        // Under §4.4.6 the same rule is rejected before anything is written.
        val e = runCatching { run(stateDependent = true) { greedyIgnoringStock } }.exceptionOrNull()
        println("§4.4.6 declaration, same rule: ${e?.let { it::class.simpleName }}")
        println("  ${(e as? ActionValidationException)?.violations ?: e?.message}")
        assertTrue(
            e != null,
            "§4.4.6 should reject an action that exceeds the state-dependent total"
        )
    }

    // ---------------------------------------------------------------- question 3

    /**
     *  §4.4.6.2 requires `isFeasible` to delegate to `prepare` rather than re-derive the
     *  test, so a rule cannot be told an action is feasible and then rejected for it. This
     *  probes the two against each other with actions drawn around the feasible boundary.
     */
    @Test
    fun isFeasibleNeverDisagreesWithWhatTheElementAccepts() {
        var checked = 0
        var disagreements = 0

        val prober = object : PolicyIfc {
            override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
                val n = ctx.leverNames.size
                val budget = ctx.budgetTotal(0) ?: 0.0
                // Candidates that straddle every boundary the element enforces.
                val candidates = listOf(
                    DoubleArray(n),                                             // all zero
                    DoubleArray(n) { ctx.actions.bounds(it).endInclusive },     // each at its max
                    DoubleArray(n) { ctx.actions.bounds(it).endInclusive + 1 }, // each just over
                    DoubleArray(n) { if (it == 0) budget else 0.0 },            // the whole budget
                    DoubleArray(n) { if (it == 0) budget + 1 else 0.0 },        // just over it
                    DoubleArray(n) { 0.5 }                                      // non-integral
                )
                for (c in candidates) {
                    checked++
                    val says = (c in ctx.actions)
                    val why = ctx.actions.violations(c)
                    // The contract: isFeasible true <=> no violations. One predicate.
                    if (says != why.isEmpty()) disagreements++
                }
                // Act feasibly so the replication proceeds.
                return DoubleArray(n) { ctx.actions.bounds(it).endInclusive }
                    .let { plan ->
                        var left = budget
                        DoubleArray(n) { i -> val g = minOf(plan[i], left); left -= g; Math.rint(g) }
                    }
            }
        }

        run(stateDependent = true) { prober }
        println()
        println("isFeasible vs violations: $checked probes, $disagreements disagreements")
        assertEquals(0, disagreements, "isFeasible and violations disagreed — they are one predicate (§4.4.6.2)")
        assertTrue(checked > 1000, "the probe did not run enough epochs to mean anything")
    }

    // ---------------------------------------------------------------- question 4

    /**
     *  The payoff. A CFA that serves the expensive region first should beat a PFA that
     *  splits proportionally, and both should beat shipping nothing. If the CFA does not
     *  win, §4.4.6 has enabled a policy class that does not pay here.
     */
    @Test
    fun theCostFunctionApproximationBeatsTheProportionalRule() {
        val (m0, d0) = run(stateDependent = true) { ShipNothing }
        val (m1, d1) = run(stateDependent = true) { ProportionalShipping(useFeasibleSet = true) }
        val (m2, d2) = run(stateDependent = true) { GreedyByShortageCost(it.shortageRates, useFeasibleSet = true) }

        val nothing = cost(m0, d0)
        val proportional = cost(m1, d1)
        val greedy = cost(m2, d2)

        println()
        println("Shortage cost per unit time (rates 9 / 3 / 1 by region):")
        println("  ship nothing                  %9.2f".format(nothing))
        println("  proportional to backlog (PFA) %9.2f".format(proportional))
        println("  greedy by cost      (CFA)     %9.2f".format(greedy))
        println("  CFA over PFA: %+.1f%%".format(100.0 * (proportional - greedy) / proportional))
        println()
        println("  over-shipments absorbed by the model, all three arms: " +
            "${d0.overShipmentsAbsorbed}, ${d1.overShipmentsAbsorbed}, ${d2.overShipmentsAbsorbed}")

        assertTrue(proportional < nothing, "the proportional rule did not beat shipping nothing")
        assertTrue(greedy < proportional, "the CFA did not beat the PFA")
        assertEquals(0, d2.overShipmentsAbsorbed,
            "under §4.4.6 the element should reject infeasible actions, so the model never absorbs one")
    }

    /**
     *  The same CFA logic written both ways: reading the feasible set, and re-deriving it
     *  from observations. They should agree — and the point is what it costs to make them
     *  agree, not whether they can.
     */
    @Test
    fun rederivingTheFeasibleSetReproducesItButDuplicatesTheModelsConstraint() {
        val (mA, dA) = run(stateDependent = true) { GreedyByShortageCost(it.shortageRates, useFeasibleSet = true) }
        val (mB, dB) = run(stateDependent = false) { GreedyByShortageCost(it.shortageRates, useFeasibleSet = false) }
        val a = cost(mA, dA)
        val b = cost(mB, dB)
        println()
        println("CFA reading 𝒳(s)      : %9.4f".format(a))
        println("CFA re-deriving 𝒳(s)  : %9.4f  (absorbed ${dB.overShipmentsAbsorbed})".format(b))
        assertTrue(Math.abs(a - b) < 1e-9, "the two formulations disagreed: $a vs $b")
    }

    /**
     *  The action set is an object with a size, and here that size genuinely moves: three
     *  levers whose bounds are backlogs and whose joint total is the stock on hand.
     *
     *  This is the test that justifies `size` being **nullable**. A VFA on this model cannot
     *  enumerate — most of the time there are more actions than anyone should walk — and the
     *  only way it can find that out without trying is to ask.
     */
    @Test
    fun theActionSetSizeTracksTheStateAndOftenExceedsWhatCanBeWalked() {
        var minSize = Long.MAX_VALUE
        var maxSize = 0L
        var enumerable = 0
        var tooLarge = 0
        var shown = 0

        val prober = object : PolicyIfc {
            override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
                val n = ctx.actions.size
                if (n == null) tooLarge++ else {
                    minSize = minOf(minSize, n); maxSize = maxOf(maxSize, n); enumerable++
                    if (shown < 3) {
                        shown++
                        val bounds = (0 until ctx.actions.leverCount)
                            .joinToString(", ") { "%.0f".format(ctx.actions.bounds(it).endInclusive) }
                        println("  backlogs ($bounds), budget %.0f -> %,d feasible actions"
                            .format(ctx.budgetTotal(0) ?: 0.0, n))
                    }
                }
                return DoubleArray(ctx.leverNames.size)
            }
        }
        run(stateDependent = true) { prober }

        val total = enumerable + tooLarge
        println("Feasible action count: min %,d, max %,d over %,d enumerable epochs".format(minSize, maxSize, enumerable))
        println("  epochs where the set exceeded the walkable ceiling: %,d of %,d (%.0f%%)"
            .format(tooLarge, total, 100.0 * tooLarge / total))

        assertTrue(total > 1000, "not enough epochs to mean anything")
        assertTrue(minSize < maxSize, "the action set size never moved, but 𝒳(s) is state-dependent here")
        assertTrue(
            tooLarge > 0,
            "the set was always enumerable, so this model does not demonstrate why size is nullable"
        )
    }
}
