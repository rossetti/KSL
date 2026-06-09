package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.InventoryHoldingPoint
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.ShipmentFormation
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.modeling.supplychain.transport.DemandLoadBuilder
import ksl.modeling.supplychain.transport.TimeBasedLoadCarrier
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Regression guard for the load-carrier in-transit reporting gap: a
 * [TimeBasedLoadCarrier] dispatches loads through its own path, so it
 * must still feed the inherited `#In Transit` and `Transit Time`
 * responses.  Before the fix both stayed at 0 / NaN under shipment
 * formation even though loads were clearly flowing.
 */
class LoadCarrierInTransitTest {

    @Test
    fun `a load carrier populates its in-transit and transit-time responses`() {
        val m = Model("LoadCarrierInTransit")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net", transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val item = net.addItemType("A", ExponentialRV(1.0, streamNum = 1))

        val wh: InventoryHoldingPoint = net.addInventoryHoldingPoint("WH", enableShipmentFormation = true)
        wh.addReorderPointReorderQuantityInventory(item, reorderPoint = 6, reorderQty = 30, initialOnHand = 40)
        net.attachToExternalSupplier(wh, ConstantRV(3.0))

        val r = net.addInventoryHoldingPoint("R")
        r.addReorderPointOrderUpToLevelInventory(item, reorderPoint = 3, orderUpToPoint = 12, initialOnHand = 10)
        // Constant 2.0 transport leg so the transit-time response is predictable.
        net.attachToSupplier(
            wh, r, ConstantRV(2.0),
            shipmentFormation = ShipmentFormation(
                DemandLoadBuilder.LoadFormingOption.COUNT, countLimit = 3,
            ),
        )
        net.attachDemandGenerator(r, item, ExponentialRV(0.5, streamNum = 5))

        m.numberOfReplications = 3
        m.lengthOfReplication = 2000.0
        m.lengthOfReplicationWarmUp = 300.0
        m.simulate()

        val loadCarrier = wh.demandCarrier as TimeBasedLoadCarrier
        val avgInTransit = loadCarrier.numInTransitResponse.withinReplicationStatistic.weightedAverage
        val avgTransitTime = loadCarrier.transitTimeResponse.acrossReplicationStatistic.average

        assertTrue(avgInTransit > 0.0, "load carrier #In Transit should be > 0 (was $avgInTransit)")
        // Every formed load travels the constant 2.0 leg.
        assertEquals(2.0, avgTransitTime, 1e-9, "transit time should equal the constant leg (2.0)")
    }
}
