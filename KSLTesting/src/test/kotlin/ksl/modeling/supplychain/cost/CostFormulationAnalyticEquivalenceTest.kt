package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Phase-3 (cost redesign) — analytic-equivalence gate: the
 * [DefaultMultiEchelonCostFormulation]'s per-line and per-tier
 * rollup Responses must match analytically-computed expected values
 * derived from the simulation's raw observables (avg-on-hand,
 * shipment counts, order counts).
 *
 * The new formulation emits raw `$/(rate-time-unit)` rates and
 * per-replication totals with **no time-unit conversion**, per the
 * design plan.  This test computes the expected values analytically
 * from the underlying observables and asserts equality against them.
 *
 * Scope: `TransportStrategy.PerIHPTimeBased`.  The
 * `NetworkTimeBased` counterpart is
 * [CostFormulationNetworkTimeBasedTest].
 */
class CostFormulationAnalyticEquivalenceTest {

    @Test
    fun `default formulation matches analytic expectations on a PerIHP topology`() {
        // Topology: ES → 1 warehouse → 2 retailers, single item.
        val m = Model("Equiv")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val item = net.addItemType("X", ConstantRV(0.25)).apply {
            unitCost = 4.0
        }
        val w = net.addInventoryHoldingPoint("W")
        w.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 2, reorderQty = 10, initialOnHand = 10,
        )
        val r1 = net.addInventoryHoldingPoint("R1")
        r1.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 5, initialOnHand = 5,
        )
        val r2 = net.addInventoryHoldingPoint("R2")
        r2.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 5, initialOnHand = 5,
        )
        net.attachToExternalSupplier(w, ConstantRV(0.25))
        net.attachToSupplier(w, r1, ConstantRV(0.5))
        net.attachToSupplier(w, r2, ConstantRV(0.5))
        net.attachDemandGenerator(
            r1, item, ConstantRV(1.0), name = "DG-R1",
            transportTime = ConstantRV.ZERO,
        )
        net.attachDemandGenerator(
            r2, item, ConstantRV(2.0), name = "DG-R2",
            transportTime = ConstantRV.ZERO,
        )

        val params = CostParams(
            carryingRate = 0.10,
            backorderRate = 5.0,
            orderingCost = 7.0,
            unloadingCost = 11.0,
            loadingCost = 13.0,
            shippingCost = 17.0,
            stockoutCost = 19.0,
            lostSaleCost = 23.0,
            unitShortageCost = 29.0,
            esLoadingCost = 31.0,
        )
        val f = DefaultMultiEchelonCostFormulation(net, params)

        m.numberOfReplications = 1
        m.lengthOfReplication = 30.0
        m.simulate()

        // -- analytic expected values from the raw observables --

        // Holding line: Σ_IHPs Σ_items avgOnHand × unitCost × carryingRate
        var expHolding = 0.0
        var expInTransit = 0.0
        var expOrdering = 0.0
        var expStockout = 0.0
        var expLostSale = 0.0
        var expUnitShortage = 0.0
        var expBackorder = 0.0
        for (ihp in net.getInventoryHoldingPoints()) {
            for (it in ihp.itemTypes) {
                val inv = ihp.getInventory(it) ?: continue
                val u = it.unitCost
                expHolding +=
                    inv.onHandResponse.withinReplicationStatistic.weightedAverage *
                        u * params.carryingRate
                expInTransit +=
                    inv.onOrderResponse.withinReplicationStatistic.weightedAverage *
                        u * params.carryingRate
                expOrdering += inv.orderCounterCounter.value * params.orderingCost
                expStockout += inv.stockoutCounter.value * params.stockoutCost
                expLostSale += inv.lostSaleCounter.value * params.lostSaleCost
                expUnitShortage +=
                    inv.totalUnitsShort.value * params.unitShortageCost
                inv.backLogPolicy?.let { policy ->
                    expBackorder += policy.avgBacklogInQ * params.backorderRate
                }
            }
        }

        // Flow lines from per-edge counters.  Walk each node's outbound
        // carrier and sum across (customer + DG) destinations.
        var expLoading = 0.0
        var expShipping = 0.0
        var expUnloadingIHP = 0.0
        var expUnloadingCD = 0.0
        for (node in net.getNodes()) {
            val carrier = node.demandCarrier as?
                ksl.modeling.supplychain.transport.TimeBasedDemandCarrier
                ?: continue
            // Outbound: every customer + DG.
            for (customer in net.customersOf(node)) {
                val n = carrier.getNumberOfDemandShipments(customer)
                expLoading += n * params.loadingCost
                expShipping += n * params.shippingCost
                // Unloading attributed to destination's tier.
                if (customer is ksl.modeling.supplychain.inventory.InventoryHoldingPoint) {
                    expUnloadingIHP += n * params.unloadingCost
                }
            }
            for (dg in net.getDemandGenerators(node)) {
                val n = carrier.getNumberOfDemandShipments(dg)
                expLoading += n * params.loadingCost
                expShipping += n * params.shippingCost
                // No Unloading for DG (no destination tier)
            }
        }

        // ES carrier: Unloading attribution per-IHP, plus ESLoading.
        val esCarrier = net.externalSupplier.demandCarrier as?
            ksl.modeling.supplychain.transport.TimeBasedDemandCarrier
        var expESLoading = 0.0
        if (esCarrier != null) {
            for (node in net.getNodes()) {
                if (!net.isAttachedToExternalSupplier(node)) continue
                val n = esCarrier.getNumberOfDemandShipments(node as ksl.modeling.supplychain.DemandSenderIfc)
                expESLoading += n * params.esLoadingCost
                if (node is ksl.modeling.supplychain.inventory.InventoryHoldingPoint) {
                    expUnloadingIHP += n * params.unloadingCost
                }
            }
        }

        // Assert per-line rollups match.
        assertEquals(expHolding,
            f.byLineResponse(CostLine.Holding)!!.value, 1e-9)
        assertEquals(expInTransit,
            f.byLineResponse(CostLine.InTransit)!!.value, 1e-9)
        assertEquals(expOrdering,
            f.byLineResponse(CostLine.Ordering)!!.value, 1e-9)
        assertEquals(expBackorder,
            f.byLineResponse(CostLine.Backorder)!!.value, 1e-9)
        assertEquals(expStockout,
            f.byLineResponse(CostLine.Stockout)!!.value, 1e-9)
        assertEquals(expLostSale,
            f.byLineResponse(CostLine.LostSale)!!.value, 1e-9)
        assertEquals(expUnitShortage,
            f.byLineResponse(CostLine.UnitShortage)!!.value, 1e-9)
        assertEquals(expLoading,
            f.byLineResponse(CostLine.Loading)!!.value, 1e-9)
        assertEquals(expShipping,
            f.byLineResponse(CostLine.Shipping)!!.value, 1e-9)
        assertEquals(expUnloadingIHP + expUnloadingCD,
            f.byLineResponse(CostLine.Unloading)!!.value, 1e-9)
        assertEquals(expESLoading,
            f.byLineResponse(CostLine.ESLoading)!!.value, 1e-9)

        // Per-tier IHP rollup = sum of all IHP-attributed line items.
        val expIHPTotal =
            expHolding + expInTransit + expOrdering + expBackorder +
            expStockout + expLostSale + expUnitShortage +
            expLoading + expShipping + expUnloadingIHP
            // ShipmentBuilderHolding is 0 here (no formation enabled)
        assertEquals(expIHPTotal,
            f.byTierResponse(NodeTier.IHP)!!.value, 1e-9)

        // Per-tier ES rollup = ESLoading.
        assertEquals(expESLoading,
            f.byTierResponse(NodeTier.ES)!!.value, 1e-9)

        // Grand total.
        val expGrand = expIHPTotal + expESLoading
        assertEquals(expGrand, f.totalCostResponse.value, 1e-9)
    }
}
