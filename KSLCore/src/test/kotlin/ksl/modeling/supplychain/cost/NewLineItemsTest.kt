package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.ItemType
import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase-5 (cost redesign) — dedicated regression tests for the
 * four new line items: Backorder, Stockout, LostSale, UnitShortage.
 *
 * Each test drives a scenario that exercises one specific line and
 * asserts the network-level accessor produces the analytic value
 * computed from the underlying observables.  These complement the
 * broader analytic-equivalence tests
 * ([CostFormulationAnalyticEquivalenceTest],
 * [CostFormulationNetworkTimeBasedTest]) by isolating each new line
 * so a regression that affects only one of them surfaces with a
 * clearer error message.
 */
class NewLineItemsTest {

    @Test
    fun `Backorder cost equals avgBacklog times backorderRate`() {
        // Tight inventory + supplier with huge lead time → demands
        // accumulate in the backlog, producing a non-zero avgBacklog.
        val (m, net, inv) = buildBackloggingScenario("Backorder")
        val params = CostParams(backorderRate = 5.0)
        DefaultMultiEchelonCostFormulation(net, params)

        m.numberOfReplications = 1
        m.lengthOfReplication = 10.5
        m.simulate()

        val backlog = inv.backLogPolicy ?: error("expected attached backlog")
        val expected = backlog.avgBacklogInQ * params.backorderRate
        assertEquals(expected,
            net.totalBackorderCostResponse!!.value, 1e-12)
        // Sanity: actual backlog accumulation occurred.
        assertTrue(backlog.avgBacklogInQ > 0.0,
            "scenario should produce positive avgBacklog")
    }

    @Test
    fun `Stockout cost equals stockoutCount times stockoutCost`() {
        val (m, net, inv) = buildBackloggingScenario("Stockout")
        val params = CostParams(stockoutCost = 7.0)
        DefaultMultiEchelonCostFormulation(net, params)

        m.numberOfReplications = 1
        m.lengthOfReplication = 10.5
        m.simulate()

        val expected = inv.stockoutCounter.value * params.stockoutCost
        assertEquals(expected,
            net.totalStockoutCostResponse!!.value, 1e-12)
        assertTrue(inv.stockoutCounter.value > 0.0,
            "scenario should produce non-zero stockoutCounter")
    }

    @Test
    fun `LostSale cost equals lostSaleCount times lostSaleCost`() {
        // permitBackLogging = false → every stockout becomes a lost
        // sale instead of being queued in the backlog.  The default
        // DemandGenerator throws on rejection (a safety check);
        // subclass to swallow the rejection events.
        val m = Model("LostSale")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val item = net.addItemType("X", ConstantRV(1000.0)).apply { unitCost = 1.0 }
        val ihp = net.addInventoryHoldingPoint("IHP")
        val inv = ihp.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 0, reorderQty = 100, initialOnHand = 2,
        )
        net.attachToExternalSupplier(ihp, ConstantRV(0.25))

        val dg = object : DemandGenerator(
            supplyChainModel = sc,
            itemType = item,
            timeUntilFirstRV = ConstantRV(1.0),
            timeBtwEventsRV = ConstantRV(1.0),
            name = "DG",
        ) {
            override fun demandRejected(demand: SupplyChainModel.Demand) {
                // Lost sale — exactly what we want to count.
            }
        }
        dg.permitBackLogging = false
        net.attachDemandGenerator(ihp, dg, transportTime = ConstantRV.ZERO)

        val params = CostParams(lostSaleCost = 11.0)
        DefaultMultiEchelonCostFormulation(net, params)

        m.numberOfReplications = 1
        m.lengthOfReplication = 5.5
        m.simulate()

        val expected = inv.lostSaleCounter.value * params.lostSaleCost
        assertEquals(expected,
            net.totalLostSaleCostResponse!!.value, 1e-12)
        assertTrue(inv.lostSaleCounter.value > 0.0,
            "scenario should produce non-zero lostSaleCounter")
    }

    @Test
    fun `UnitShortage cost equals totalUnitsShort times unitShortageCost`() {
        val (m, net, inv) = buildBackloggingScenario("UnitShortage")
        val params = CostParams(unitShortageCost = 13.0)
        DefaultMultiEchelonCostFormulation(net, params)

        m.numberOfReplications = 1
        m.lengthOfReplication = 10.5
        m.simulate()

        val expected = inv.totalUnitsShort.value * params.unitShortageCost
        assertEquals(expected,
            net.totalUnitShortageCostResponse!!.value, 1e-12)
        assertTrue(inv.totalUnitsShort.value > 0.0,
            "scenario should produce non-zero totalUnitsShort")
    }

    // ----------------------------------------------------------------- setup

    /**
     * Builds an ES → IHP topology with a tight (s, Q) inventory and
     * a supplier whose lead time is too long to deliver during the
     * simulated horizon — so stockouts accumulate, demands backlog,
     * and the unit-shortage counter rises.  Used by three of the four
     * new-line-item tests (the LostSale test needs a rejecting
     * generator, which is structurally different and is built inline
     * in that test).
     */
    private fun buildBackloggingScenario(
        name: String,
    ): Triple<Model, MultiEchelonNetwork, Inventory> {
        val m = Model(name)
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val item = net.addItemType("X", ConstantRV(1000.0))
            .apply { unitCost = 1.0 }
        val ihp = net.addInventoryHoldingPoint("IHP")
        val inv = ihp.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 0, reorderQty = 100, initialOnHand = 2,
        )
        net.attachToExternalSupplier(ihp, ConstantRV(0.25))
        net.attachDemandGenerator(
            ihp, item, ConstantRV(1.0), name = "DG",
            transportTime = ConstantRV.ZERO,
        )
        return Triple(m, net, inv)
    }
}
