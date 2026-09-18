package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.inventory.DemandGenerator
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
 *  These were first written against the grand total and watched failing, by
 *  exactly the rate-line sum: 5608.4 where 5608.8 was required, short by the
 *  0.40 that `HOLDING` and `IN_TRANSIT` contributed identically to both runs.
 *  They now assert the same properties of the totals that replaced it.
 *
 *  See `.claude/plans/kslcore-costing-change-plan.md` §11, step 0.
 */
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

        fun total(form: TotalForm): Double =
            formulation.totalCostResponse(form).value
    }

    companion object {
        private const val WARM_UP = 20.0
        private const val SHORT = 40.0
        private const val LONG = 80.0

        /** For the early-ending fixture: one demand per unit time, then silence. */
        private const val DEMAND_COUNT = 60L

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
        val shortTotal = short.total(TotalForm.HorizonTotal)
        val longTotal = long.total(TotalForm.HorizonTotal)
        assertEquals(
            2.0 * shortTotal,
            longTotal,
            2.0 * shortTotal * 1.0e-6,
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

        val shortRate = short.total(TotalForm.Rate)
        val longRate = long.total(TotalForm.Rate)

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

    /**
     *  A replication whose event calendar empties before its nominal length,
     *  built by capping the demand generator. The window to scale by is the one
     *  the run observed, not the one it was allowed.
     */
    private class EarlyEndingFixture {
        val model = Model("CostBasisEarlyEnd")
        val formulation: DefaultMultiEchelonCostFormulation

        init {
            val sc = SupplyChainModel(model, name = "SC")
            // SharedCarrier rather than PerIHPTimeBased: a time-based carrier
            // reschedules itself forever, so the calendar would never empty and
            // the run could not end early.
            val net = MultiEchelonNetwork(
                sc, name = "Net",
                transportStrategy = TransportStrategy.SharedCarrier(),
            )
            val item = net.addItemType("A", ConstantRV(0.25))
            val ihp = net.addInventoryHoldingPoint("P")
            ihp.addReorderPointReorderQuantityInventory(
                item, reorderPoint = 1, reorderQty = 5, initialOnHand = 5,
            )
            net.attachToExternalSupplier(ihp)
            val generator = DemandGenerator(
                supplyChainModel = sc,
                itemType = item,
                timeUntilFirstRV = ConstantRV(1.0),
                timeBtwEventsRV = ConstantRV(1.0),
                maxNumberOfEvents = DEMAND_COUNT,
                name = "DG",
            )
            net.attachDemandGenerator(ihp, generator)
            formulation = DefaultMultiEchelonCostFormulation(net, name = "Costs")
            model.numberOfReplications = 1
            model.lengthOfReplicationWarmUp = WARM_UP
            // No fixed horizon at all: KSL schedules an end-of-replication event
            // only for a finite length, so the run ends when the calendar empties
            // after the last capped demand. There is no nominal length left to
            // mistake the observed window for.
            model.lengthOfReplication = Double.POSITIVE_INFINITY
        }
    }

    @Test
    @DisplayName("scaling uses the window observed, not the one the replication was allowed")
    fun scalingUsesObservedTimeNotNominalLength() {
        val f = EarlyEndingFixture()
        f.model.simulate()

        val endTime = f.model.time
        val observed = endTime - WARM_UP

        // Without this the test would pass vacuously: the run has to actually
        // stop on its own, after the capped demand stream runs dry.
        assertTrue(
            endTime.isFinite() && observed > 0.0,
            "the run did not end on an empty calendar: ended at $endTime"
        )

        val lines = CostLine.all.associateWith { f.formulation.byLineResponse(it)?.value ?: 0.0 }
        val rateSum = lines.filterKeys { it.basis == CostBasis.RatePerTime }.values.sum()
        val eventSum = lines.filterKeys { it.basis == CostBasis.PerReplicationTotal }.values.sum()
        assertTrue(rateSum > 0.0, "no rate-based cost accrued, so the scale factor is unobservable")

        val horizonTotal = f.formulation.totalCostResponse(TotalForm.HorizonTotal).value

        // Invert the total to recover the window it was scaled by, and check that
        // window is the observed one. Scaling by the nominal length instead would
        // put this out by the ratio of the two.
        val impliedWindow = (horizonTotal - eventSum) / rateSum
        assertTrue(
            impliedWindow.isFinite(),
            "the total was scaled by a non-finite window, so it was taken from the " +
                "replication's nominal length rather than from the clock"
        )
        assertEquals(
            observed,
            impliedWindow,
            observed * 1.0e-9,
            "the total was scaled by $impliedWindow, but the run observed $observed"
        )
    }

    @Test
    @DisplayName("the two forms are the same quantity, read two ways")
    fun theTwoFormsAgreeWithEachOther() {
        for (window in listOf(SHORT, LONG)) {
            val f = Fixture(window).run()
            // Nothing else pins the two forms to each other, so without this they
            // could drift apart -- each internally consistent across horizons, and
            // disagreeing about the same run.
            assertEquals(
                f.total(TotalForm.Rate) * window,
                f.total(TotalForm.HorizonTotal),
                f.total(TotalForm.HorizonTotal) * 1.0e-9,
                "rate times the observed window must reproduce the horizon total, " +
                    "at window $window"
            )
        }
    }
}
