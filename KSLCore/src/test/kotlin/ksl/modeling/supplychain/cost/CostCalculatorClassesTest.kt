package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.ItemType
import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.ShipmentFormation
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.modeling.supplychain.transport.DemandLoadBuilder
import ksl.modeling.supplychain.transport.TimeBasedDemandCarrier
import ksl.modeling.supplychain.transport.TimeBasedLoadCarrier
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Phase-3 (cost redesign) — confirms each of the six concrete
 * [CostCalculator] subclasses computes the right line-item values
 * from its source's stabilized within-replication statistics.
 *
 * Each test hand-attaches a calculator to a small scenario, runs
 * `simulate()`, and asserts the calculator's `lineResponses[*]`
 * values against analytic expectations.  The calculators are
 * exercised individually here; the integration with
 * [DefaultMultiEchelonCostFormulation.buildCalculators] is the
 * subject of Phase-3 Commit B's tests.
 */
class CostCalculatorClassesTest {

    @Test
    fun `InventoryCostCalculator computes six line items correctly`() {
        // Reuse the StockoutCounterTest scenario:
        //   - initialOnHand = 2, reorderPoint = 0, reorderQty = 100
        //   - 5 demands (1 unit each) at t = 1, 2, 3, 4, 5
        //   - first 2 fill, last 3 stockout and backlog
        //   - exactly 1 replenishment order for 100 units fires
        //     when on-hand hits 0
        val m = Model("InvCalc")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "X").apply { unitCost = 4.0 }
        val supplier = LeadTimeDemandFiller(sc, name = "Supplier")
        supplier.addLeadTime(item, ConstantRV(1000.0))

        val inv = Inventory.createReorderPointReorderQuantityInventory(
            parent = sc, itemType = item,
            reorderPoint = 0, reorderQty = 100, initialOnHand = 2,
            name = "INV",
        )
        inv.demandFiller = supplier

        val dg = DemandGenerator(
            supplyChainModel = sc, itemType = item,
            timeUntilFirstRV = ConstantRV(1.0),
            timeBtwEventsRV = ConstantRV(1.0),
            name = "DG",
        )
        dg.demandFiller = inv

        val params = CostParams(
            carryingRate = 0.10,
            orderingCost = 7.0,
            stockoutCost = 11.0,
            lostSaleCost = 13.0,
            unitShortageCost = 17.0,
        )
        val calc = InventoryCostCalculator(m, inv, params)

        m.numberOfReplications = 1
        m.lengthOfReplication = 5.5
        m.simulate()

        // Stockouts = 3, lost sales = 0 (all backlogged), units short = 3,
        // order count = 1, units ordered = 100.
        assertEquals(0.0, calc.lineResponses[CostLine.LostSale]!!.value, 1e-12)
        assertEquals(3.0 * 11.0,
            calc.lineResponses[CostLine.Stockout]!!.value, 1e-12)
        assertEquals(3.0 * 17.0,
            calc.lineResponses[CostLine.UnitShortage]!!.value, 1e-12)
        assertEquals(1.0 * 7.0,
            calc.lineResponses[CostLine.Ordering]!!.value, 1e-12)

        // Holding / InTransit derived from the live TW averages.
        val expectedHolding =
            inv.onHandResponse.withinReplicationStatistic.weightedAverage *
                4.0 * 0.10
        val expectedInTransit =
            inv.onOrderResponse.withinReplicationStatistic.weightedAverage *
                4.0 * 0.10
        assertEquals(expectedHolding,
            calc.lineResponses[CostLine.Holding]!!.value, 1e-12)
        assertEquals(expectedInTransit,
            calc.lineResponses[CostLine.InTransit]!!.value, 1e-12)

        // Source and tier are exposed.
        assertSame(inv, calc.source)
        assertSame(NodeTier.IHP, calc.tier)
    }

    @Test
    fun `BackorderCostCalculator computes avgBacklog times backorderRate`() {
        // Same setup as the InventoryCostCalculator test.  Reuse the
        // factory-attached BackLogQueue.
        val m = Model("BackCalc")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "X")
        val supplier = LeadTimeDemandFiller(sc, name = "Supplier")
        supplier.addLeadTime(item, ConstantRV(1000.0))

        val inv = Inventory.createReorderPointReorderQuantityInventory(
            parent = sc, itemType = item,
            reorderPoint = 0, reorderQty = 100, initialOnHand = 2,
            name = "INV",
        )
        inv.demandFiller = supplier

        val dg = DemandGenerator(
            supplyChainModel = sc, itemType = item,
            timeUntilFirstRV = ConstantRV(1.0),
            timeBtwEventsRV = ConstantRV(1.0),
            name = "DG",
        )
        dg.demandFiller = inv

        val backlog = inv.backLogPolicy ?: error("expected attached backlog")
        val params = CostParams(backorderRate = 3.0)
        val calc = BackorderCostCalculator(m, backlog, params)

        m.numberOfReplications = 1
        m.lengthOfReplication = 5.5
        m.simulate()

        val expected = backlog.avgBacklogInQ * 3.0
        assertEquals(expected,
            calc.lineResponses[CostLine.Backorder]!!.value, 1e-12)
        assertSame(backlog, calc.source)
        assertSame(NodeTier.IHP, calc.tier)
    }

    @Test
    fun `EdgeOutboundCostCalculator computes Loading and Shipping`() {
        val m = Model("EdgeOut")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val item = net.addItemType("X", ConstantRV(0.25))
        val ihp = net.addInventoryHoldingPoint("IHP")
        ihp.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 5, initialOnHand = 5,
        )
        net.attachToExternalSupplier(ihp, ConstantRV(0.5))
        net.attachDemandGenerator(
            ihp, item, ConstantRV(1.0), name = "DG",
            transportTime = ConstantRV.ZERO,
        )

        val esCarrier = net.externalSupplier.demandCarrier as TimeBasedDemandCarrier
        val params = CostParams(loadingCost = 10.0, shippingCost = 20.0)
        val calc = EdgeOutboundCostCalculator(
            m, esCarrier, ihp, NodeTier.ES, params,
        )

        m.numberOfReplications = 1
        m.lengthOfReplication = 30.0
        m.simulate()

        val shipments = esCarrier.getNumberOfDemandShipments(ihp)
        assertEquals(shipments * 10.0,
            calc.lineResponses[CostLine.Loading]!!.value, 1e-12)
        assertEquals(shipments * 20.0,
            calc.lineResponses[CostLine.Shipping]!!.value, 1e-12)
        assertSame(NodeTier.ES, calc.tier)
    }

    @Test
    fun `EdgeInboundCostCalculator computes Unloading at destination's tier`() {
        val m = Model("EdgeIn")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val item = net.addItemType("X", ConstantRV(0.25))
        val ihp = net.addInventoryHoldingPoint("IHP")
        ihp.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 5, initialOnHand = 5,
        )
        net.attachToExternalSupplier(ihp, ConstantRV(0.5))
        net.attachDemandGenerator(
            ihp, item, ConstantRV(1.0), name = "DG",
            transportTime = ConstantRV.ZERO,
        )

        val esCarrier = net.externalSupplier.demandCarrier as TimeBasedDemandCarrier
        val params = CostParams(unloadingCost = 9.0)
        // ES → IHP edge: destination tier is IHP (priced as IHP unloading)
        val calc = EdgeInboundCostCalculator(
            m, esCarrier, ihp, NodeTier.IHP, params,
        )

        m.numberOfReplications = 1
        m.lengthOfReplication = 30.0
        m.simulate()

        val shipments = esCarrier.getNumberOfDemandShipments(ihp)
        assertEquals(shipments * 9.0,
            calc.lineResponses[CostLine.Unloading]!!.value, 1e-12)
        // Tier attribution = destination's tier (IHP), not the supplier's
        // (ES).
        assertSame(NodeTier.IHP, calc.tier)
    }

    @Test
    fun `BuilderCostCalculator computes per-item builder holding cost`() {
        val m = Model("Builder")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val item = net.addItemType("X", ConstantRV(0.25)).apply { unitCost = 8.0 }
        val supplier = net.addInventoryHoldingPoint(
            "P", enableShipmentFormation = true,
        )
        supplier.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 20, initialOnHand = 50,
        )
        net.attachToExternalSupplier(supplier, ConstantRV(0.25))
        net.attachDemandGenerator(
            supplier, item, ConstantRV(1.0), name = "DG",
            transportTime = ConstantRV.ZERO,
            shipmentFormation = ShipmentFormation(
                DemandLoadBuilder.LoadFormingOption.COUNT, countLimit = 5,
            ),
        )

        val carrier = supplier.demandCarrier as TimeBasedLoadCarrier
        val builder = carrier.allLoadBuilders().single()
        val params = CostParams(carryingRate = 0.10)
        val calc = BuilderCostCalculator(m, builder, NodeTier.IHP, params)

        m.numberOfReplications = 1
        m.lengthOfReplication = 30.0
        m.simulate()

        // Σ_items avgUnitsOnHand × unitCost × carryingRate
        var expected = 0.0
        for (it in builder.trackedItemTypes) {
            val twr = builder.unitsOnHandResponse(it)!!
            val avg = twr.withinReplicationStatistic.weightedAverage
            expected += avg * it.unitCost * 0.10
        }
        assertEquals(expected,
            calc.lineResponses[CostLine.ShipmentBuilderHolding]!!.value, 1e-12)
    }

    @Test
    fun `ESCostCalculator sums shipments across destinations and prices them`() {
        val m = Model("ESCalc")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val item = net.addItemType("X", ConstantRV(0.25))
        val ihpA = net.addInventoryHoldingPoint("A")
        val ihpB = net.addInventoryHoldingPoint("B")
        ihpA.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 5, initialOnHand = 5,
        )
        ihpB.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 5, initialOnHand = 5,
        )
        net.attachToExternalSupplier(ihpA, ConstantRV(0.5))
        net.attachToExternalSupplier(ihpB, ConstantRV(0.5))
        net.attachDemandGenerator(
            ihpA, item, ConstantRV(1.0), name = "DG-A",
            transportTime = ConstantRV.ZERO,
        )
        net.attachDemandGenerator(
            ihpB, item, ConstantRV(2.0), name = "DG-B",
            transportTime = ConstantRV.ZERO,
        )

        val esCarrier = net.externalSupplier.demandCarrier as TimeBasedDemandCarrier
        val params = CostParams(esLoadingCost = 5.0)
        val calc = ESCostCalculator(
            m, esCarrier, listOf(ihpA, ihpB), params,
        )

        m.numberOfReplications = 1
        m.lengthOfReplication = 30.0
        m.simulate()

        val totalShipments =
            esCarrier.getNumberOfDemandShipments(ihpA) +
            esCarrier.getNumberOfDemandShipments(ihpB)
        assertEquals(totalShipments * 5.0,
            calc.lineResponses[CostLine.ESLoading]!!.value, 1e-12)
        assertSame(NodeTier.ES, calc.tier)
    }
}
