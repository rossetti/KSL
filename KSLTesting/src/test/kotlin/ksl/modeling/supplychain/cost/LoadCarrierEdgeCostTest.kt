package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.ShipmentFormation
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.modeling.supplychain.transport.DemandLoadBuilder
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Regression guard: per-edge flow costs (Loading / Shipping /
 * Unloading) must be non-zero on a formation-enabled supplier's
 * outbound edges.
 *
 * The bug: [ksl.modeling.supplychain.transport.TimeBasedLoadCarrier]
 * records dispatched *loads* in its own per-destination shipment
 * counter, but the per-edge cost calculators read
 * `getNumberOfDemandShipments`, which a load carrier (before this fix)
 * did not override — so it returned the parent's never-incremented
 * per-demand counter, leaving every Loading/Shipping/Unloading line at
 * zero whenever shipment formation was enabled.
 */
class LoadCarrierEdgeCostTest {

    @Test
    fun `formation-enabled supplier edges accrue loading, shipping, and unloading cost`() {
        val m = Model("LoadCarrierEdgeCost")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net", transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val item = net.addItemType("A", ExponentialRV(1.0, streamNum = 1))

        // Warehouse forms loads on its outbound legs.
        val wh = net.addInventoryHoldingPoint("WH", enableShipmentFormation = true)
        wh.addReorderPointReorderQuantityInventory(item, reorderPoint = 6, reorderQty = 30, initialOnHand = 40)
        net.attachToExternalSupplier(wh, ConstantRV(3.0))

        // Retailer fed by formed loads (count-of-3 bundles).
        val r = net.addInventoryHoldingPoint("R")
        r.addReorderPointOrderUpToLevelInventory(item, reorderPoint = 3, orderUpToPoint = 12, initialOnHand = 10)
        net.attachToSupplier(
            wh, r, ConstantRV(1.0),
            shipmentFormation = ShipmentFormation(
                DemandLoadBuilder.LoadFormingOption.COUNT, countLimit = 3,
            ),
        )
        net.attachDemandGenerator(r, item, ExponentialRV(0.5, streamNum = 5))

        val cost = DefaultMultiEchelonCostFormulation(
            net, CostParams(loadingCost = 40.0, shippingCost = 15.0, unloadingCost = 30.0),
        )

        m.numberOfReplications = 3
        m.lengthOfReplication = 2000.0
        m.lengthOfReplicationWarmUp = 300.0
        m.simulate()

        val loading = cost.byTierAndLineResponse(NodeTier.IHP, CostLine.Loading)!!
            .acrossReplicationStatistic.average
        val shipping = cost.byTierAndLineResponse(NodeTier.IHP, CostLine.Shipping)!!
            .acrossReplicationStatistic.average
        val unloading = cost.byTierAndLineResponse(NodeTier.IHP, CostLine.Unloading)!!
            .acrossReplicationStatistic.average

        assertTrue(loading > 0.0, "IHP-tier Loading should be > 0 under formation (was $loading)")
        assertTrue(shipping > 0.0, "IHP-tier Shipping should be > 0 under formation (was $shipping)")
        assertTrue(unloading > 0.0, "IHP-tier Unloading should be > 0 under formation (was $unloading)")
    }
}
