package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.modeling.supplychain.transport.TimeBasedDemandCarrier
import ksl.observers.ModelElementObserver
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Regression for the immediate-transport per-destination stat loss
 * (audit finding D).  A [TimeBasedDemandCarrier]'s per-destination
 * shipment / weight / cube counters were created only in
 * `setTransportTime`.  When an edge was attached *without* a transport
 * time (so the network sets `immediateTransportFlag` instead of
 * registering the destination), shipments on that edge delivered
 * correctly but the `?.increment()` on the absent counters silently
 * no-opped — the per-destination stats undercounted with no error.
 *
 * The fix extracts counter creation into `registerDestination`, which
 * the network now calls on every immediate-transport edge.
 *
 * Setup: ES → W → R under PerIHPTimeBased, with the W→R edge attached
 * *without* a transport time (immediate).  R reorders from W; every
 * replenishment W ships to R must register on W's per-destination
 * shipment counter.
 */
class ImmediateTransportStatTest {

    @Test
    fun `immediate-transport edge still records per-destination shipment counts`() {
        val m = Model("ImmTransportStat")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val item = net.addItemType("X", ConstantRV(0.25))

        val w = net.addInventoryHoldingPoint("W")
        w.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 2, reorderQty = 20, initialOnHand = 50,
        )
        net.attachToExternalSupplier(w, ConstantRV(0.25))

        val r = net.addInventoryHoldingPoint("R")
        r.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 5, initialOnHand = 5,
        )
        // Immediate transport on the W -> R edge (no transportTime).
        net.attachToSupplier(w, r)
        net.attachDemandGenerator(
            r, item, ConstantRV(1.0), name = "DG",
            transportTime = ConstantRV.ZERO,
        )

        val wCarrier = w.demandCarrier as TimeBasedDemandCarrier
        var shipmentsToR = -1.0
        var weightToR = -1.0
        wCarrier.attachModelElementObserver(object : ModelElementObserver() {
            override fun replicationEnded(me: ModelElement) {
                shipmentsToR = wCarrier.getNumberOfDemandShipments(r)
                weightToR = wCarrier.totalLoadWeightAccumulator(r)?.value ?: -1.0
            }
        })

        m.numberOfReplications = 1
        m.lengthOfReplication = 30.0
        m.simulate()

        // R reorders from W repeatedly; each replenishment W ships to R
        // travels on W's immediate-transport edge and must be counted.
        assertTrue(shipmentsToR > 0.0,
            "immediate-transport edge must still count shipments to R " +
                "(got $shipmentsToR)")
        // The weight accumulator (also created by registerDestination)
        // must likewise have recorded the shipped weight.
        assertTrue(weightToR > 0.0,
            "immediate-transport edge must still accumulate shipped weight " +
                "(got $weightToR)")
    }
}
