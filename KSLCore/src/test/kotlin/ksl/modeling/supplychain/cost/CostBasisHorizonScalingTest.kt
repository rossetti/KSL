package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 *  States the mixed-basis costing defect as a property rather than as an anecdote.
 *
 *  Four of the twelve cost lines are denominated in $/time (`Holding`, `InTransit`,
 *  `Backorder`, `ShipmentBuilderHolding`); the other eight are $ per replication.
 *  The grand total sums all twelve regardless, so it is neither a total nor a rate:
 *  doubling the observed window doubles the event lines while leaving the rate lines
 *  where they were, and the sum lands somewhere in between.
 *
 *  The fixture is fully deterministic — every `ConstantRV` — and every horizon here
 *  is a whole number of the inventory's five-unit replenishment cycle, so the
 *  time-weighted averages are identical across the two runs to within solver noise.
 *  That is what lets this assert exact scaling instead of a confidence interval.
 *
 *  Both tests are written against `totalCostResponse` and both fail today, by
 *  exactly the rate-line sum. They are disabled only so that the intervening
 *  step can gate on a green suite; Change B retargets them at the new totals
 *  and re-enables them, and that diff is the evidence Change B worked.
 *
 *  See `.claude/plans/kslcore-costing-change-plan.md` §11, step 0.
 */
@Disabled("Enabled by Change B, which ships the totals these assert; see plan §12 step 2")
class CostBasisHorizonScalingTest {

    /**
     *  The single-node PerIHPTimeBased network from `CostFormulationControlsTest`,
     *  parameterized by its observed window. The warm-up is held fixed so that the
     *  two runs differ in observed time and in nothing else.
     */
    private class Fixture(observedWindow: Double) {
        val model = Model("CostBasisHorizon")
        val formulation: DefaultMultiEchelonCostFormulation

        init {
            val sc = SupplyChainModel(model, name = "SC")
            val net = MultiEchelonNetwork(
                sc, name = "Net",
                transportStrategy = TransportStrategy.PerIHPTimeBased,
            )
            val item = net.addItemType("A", ConstantRV(0.25))
            val ihp = net.addInventoryHoldingPoint("P")
            ihp.addReorderPointReorderQuantityInventory(
                item, reorderPoint = 1, reorderQty = 5, initialOnHand = 5,
            )
            net.attachToExternalSupplier(ihp, ConstantRV(0.25))
            net.attachDemandGenerator(
                ihp, item, ConstantRV(1.0), name = "DG",
                transportTime = ConstantRV.ZERO,
            )
            formulation = DefaultMultiEchelonCostFormulation(net, name = "Costs")
            model.numberOfReplications = 1
            model.lengthOfReplicationWarmUp = WARM_UP
            model.lengthOfReplication = WARM_UP + observedWindow
        }

        fun run(): Fixture {
            model.simulate()
            return this
        }

        /** Every cost line's end-of-replication value, by line. */
        fun lines(): Map<CostLine, Double> =
            CostLine.all.associateWith { formulation.byLineResponse(it)?.value ?: 0.0 }

        val grandTotal: Double get() = formulation.totalCostResponse.value
    }

    companion object {
        private const val WARM_UP = 20.0
        private const val SHORT = 40.0
        private const val LONG = 80.0

        /** The four lines the calculators form as a time-weighted average times a rate. */
        private val RATE_LINES = setOf(
            CostLine.Holding, CostLine.InTransit,
            CostLine.Backorder, CostLine.ShipmentBuilderHolding,
        )
    }

    /**
     *  Renders what each line did across the two horizons, so a failure explains
     *  itself: rate lines should hold near 1.0, event lines near 2.0, and a
     *  dimensionally sound total must sit at one or the other, not between them.
     */
    private fun report(short: Map<CostLine, Double>, long: Map<CostLine, Double>): String {
        val sb = StringBuilder("\n  line                      short(${SHORT})      long(${LONG})     ratio  expected\n")
        for (line in CostLine.all) {
            val s = short[line] ?: 0.0
            val l = long[line] ?: 0.0
            if (abs(s) < 1e-12 && abs(l) < 1e-12) continue
            val ratio = if (abs(s) < 1e-12) Double.NaN else l / s
            val expect = if (line in RATE_LINES) "1.0 (\$/time)" else "2.0 (\$)"
            sb.append("  %-24s %12.4f %12.4f %9.4f  %s%n".format(line.displayName, s, l, ratio, expect))
        }
        return sb.toString()
    }

    @Test
    @DisplayName("doubling the observed window doubles a dimensionally sound total")
    fun totalScalesLinearlyWithTheObservedWindow() {
        val short = Fixture(SHORT).run()
        val long = Fixture(LONG).run()

        val shortLines = short.lines()
        val longLines = long.lines()
        val detail = report(shortLines, longLines)

        // The fixture has to actually exercise both kinds of line, or the property
        // below would hold vacuously.
        val rateSum = RATE_LINES.sumOf { shortLines[it] ?: 0.0 }
        val eventSum = CostLine.all.filter { it !in RATE_LINES }.sumOf { shortLines[it] ?: 0.0 }
        assertTrue(rateSum > 0.0, "fixture accrued no rate-based cost; the property would be vacuous$detail")
        assertTrue(eventSum > 0.0, "fixture accrued no event-based cost; the property would be vacuous$detail")

        // The property. A total denominated in dollars over the observed window must
        // double when the window doubles. The grand total does not, because its rate
        // lines contribute the same number to both runs.
        assertEquals(
            2.0 * short.grandTotal,
            long.grandTotal,
            2.0 * short.grandTotal * 1.0e-6,
            "the total did not scale with the observed window, so it is not denominated " +
                "in dollars over that window$detail"
        )
    }

    @Test
    @DisplayName("a cost rate is invariant to the observed window")
    fun rateIsInvariantToTheObservedWindow() {
        val short = Fixture(SHORT).run()
        val long = Fixture(LONG).run()
        val detail = report(short.lines(), long.lines())

        val shortRate = short.grandTotal / SHORT
        val longRate = long.grandTotal / LONG

        // The complementary half: read as a rate, the same quantity must not move
        // when the window changes. The grand total fails this too -- it is affine in
        // the horizon, so it is neither form.
        assertEquals(
            shortRate,
            longRate,
            abs(shortRate) * 1.0e-6,
            "the implied cost rate moved when the observed window changed$detail"
        )
    }
}
