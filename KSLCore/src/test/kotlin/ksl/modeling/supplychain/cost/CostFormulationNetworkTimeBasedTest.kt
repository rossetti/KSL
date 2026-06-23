package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.modeling.supplychain.transport.TimeBasedNetworkDemandCarrier
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Phase-3.5 (cost redesign) — analytic-equivalence gate for the
 * `NetworkTimeBased` transport strategy: with the shared
 * [TimeBasedNetworkDemandCarrier] carrying every edge in the network,
 * [DefaultMultiEchelonCostFormulation] must instantiate the
 * network-typed calculator classes (Network* variants) so the
 * per-line rollups still match analytic expectations derived from
 * the raw observables.
 *
 * Mirrors the [CostFormulationAnalyticEquivalenceTest] shape but
 * uses `NetworkTimeBased(carrier)` strategy.  Per-edge counts on
 * the shared carrier are keyed by `(filler, sender)` rather than
 * by destination alone.
 */
class CostFormulationNetworkTimeBasedTest {

    @Test
    fun `default formulation matches analytic expectations under NetworkTimeBased`() {
        val m = Model("EquivNet")
        val sc = SupplyChainModel(m, name = "SC")
        val sharedCarrier = TimeBasedNetworkDemandCarrier(sc, name = "NetC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.NetworkTimeBased(sharedCarrier),
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
        // Inventory-side lines are strategy-independent.
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

        // Flow lines: read per-(filler, sender) counts on the shared carrier.
        var expLoading = 0.0
        var expShipping = 0.0
        var expUnloadingIHP = 0.0

        // Node → node and node → DG outbound edges.
        for (node in net.getNodes()) {
            val nodeAsFiller = node as ksl.modeling.supplychain.DemandFillerIfc
            for (customer in net.customersOf(node)) {
                val n = sharedCarrier.getNumberOfDemandShipments(
                    nodeAsFiller, customer,
                )
                expLoading += n * params.loadingCost
                expShipping += n * params.shippingCost
                if (customer is ksl.modeling.supplychain.inventory.InventoryHoldingPoint) {
                    expUnloadingIHP += n * params.unloadingCost
                }
            }
            for (dg in net.getDemandGenerators(node)) {
                val n = sharedCarrier.getNumberOfDemandShipments(
                    nodeAsFiller, dg,
                )
                expLoading += n * params.loadingCost
                expShipping += n * params.shippingCost
            }
        }

        // ES → IHP edges.
        var expESLoading = 0.0
        val esFiller = net.externalSupplier
        for (node in net.getNodes()) {
            if (!net.isAttachedToExternalSupplier(node)) continue
            val n = sharedCarrier.getNumberOfDemandShipments(
                esFiller,
                node as ksl.modeling.supplychain.DemandSenderIfc,
            )
            expESLoading += n * params.esLoadingCost
            if (node is ksl.modeling.supplychain.inventory.InventoryHoldingPoint) {
                expUnloadingIHP += n * params.unloadingCost
            }
        }

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
        assertEquals(expUnloadingIHP,
            f.byLineResponse(CostLine.Unloading)!!.value, 1e-9)
        assertEquals(expESLoading,
            f.byLineResponse(CostLine.ESLoading)!!.value, 1e-9)

        // IHP-tier rollup.
        val expIHPTotal =
            expHolding + expInTransit + expOrdering + expBackorder +
            expStockout + expLostSale + expUnitShortage +
            expLoading + expShipping + expUnloadingIHP
        assertEquals(expIHPTotal,
            f.byTierResponse(NodeTier.IHP)!!.value, 1e-9)
        assertEquals(expESLoading,
            f.byTierResponse(NodeTier.ES)!!.value, 1e-9)
        assertEquals(expIHPTotal + expESLoading,
            f.totalCostResponse.value, 1e-9)
    }
}
