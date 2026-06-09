package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for the new per-destination / per-edge demand-shipment
 * counters added to [TimeBasedDemandCarrier] and
 * [TimeBasedNetworkDemandCarrier] in Phase 1.
 *
 * The counters underpin Phase 1's cost-model wiring on
 * [MultiEchelonNetwork] — shipping / loading / unloading costs
 * are computed from the per-edge demand-shipment count.
 */
class DemandShipmentCounterTest {

    @Test
    fun `TimeBasedDemandCarrier lazily creates a per-destination counter on setTransportTime`() {
        val sc = SupplyChainModel(Model("DSC.Lazy"))
        val carrier = TimeBasedDemandCarrier(sc, name = "C")
        val item = ItemType(sc, name = "A")
        val customer = object : DemandSenderIfc {
            override val id = 0
            override val name = "Cust"
            override var label: String? = null
            override fun mightRequest(type: ItemType) = false
            override var demandFillerFinder: DemandFillerFinderIfc? = null
            override var demandFiller: DemandFillerIfc? = null
        }
        // Pre-registration: no counter yet.
        assertNull(carrier.getDemandShipmentCounter(customer))
        carrier.setTransportTime(customer, ConstantRV(0.5))
        // After registration: counter exists, value 0.
        val counter = carrier.getDemandShipmentCounter(customer)
        assertNotNull(counter)
        assertEquals(0.0, carrier.getNumberOfDemandShipments(customer))
    }

    @Test
    fun `TimeBasedNetworkDemandCarrier lazily creates a per-edge counter`() {
        val sc = SupplyChainModel(Model("DSC.NetLazy"))
        val carrier = TimeBasedNetworkDemandCarrier(sc, name = "NC")
        val filler = LeadTimeDemandFiller(sc, name = "ES")
        val customer = object : DemandSenderIfc {
            override val id = 0
            override val name = "Cust"
            override var label: String? = null
            override fun mightRequest(type: ItemType) = false
            override var demandFillerFinder: DemandFillerFinderIfc? = null
            override var demandFiller: DemandFillerIfc? = null
        }
        assertNull(carrier.getDemandShipmentCounter(filler, customer))
        carrier.setTransportTime(filler, customer, ConstantRV(0.5))
        val counter = carrier.getDemandShipmentCounter(filler, customer)
        assertNotNull(counter)
        assertEquals(0.0, carrier.getNumberOfDemandShipments(filler, customer))
    }

    @Test
    fun `per-destination counter increments on each demand-shipment in a PerIHPTimeBased network`() {
        val m = Model("DSC.PerIHP")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val item = net.addItemType("A", ConstantRV(0.5))
        val ihp = net.addInventoryHoldingPoint("P")
        ihp.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 5, initialOnHand = 5,
        )
        net.attachToExternalSupplier(ihp, ConstantRV(0.25))
        val gen = net.attachDemandGenerator(
            ihp, item, ConstantRV(1.0), name = "DG",
            transportTime = ConstantRV(0.0),
        )

        m.numberOfReplications = 1
        m.lengthOfReplication = 12.0
        m.simulate()

        // The IHP's own carrier counts demands shipped to its
        // downstream generator. Twelve unit time, one demand per
        // unit time = approx 12 demands shipped.
        val ihpCarrier = ihp.demandCarrier as TimeBasedDemandCarrier
        val count = ihpCarrier.getNumberOfDemandShipments(gen)
        assert(count >= 10.0) { "expected >= 10 demand shipments, got $count" }
    }

    @Test
    fun `per-edge counter increments on each demand-shipment in a NetworkTimeBased network`() {
        val m = Model("DSC.NetEdge")
        val sc = SupplyChainModel(m, name = "SC")
        val carrier = TimeBasedNetworkDemandCarrier(sc, name = "NC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.NetworkTimeBased(carrier),
        )
        val item = net.addItemType("A", ConstantRV(0.5))
        val ihp = net.addInventoryHoldingPoint("P")
        ihp.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 5, initialOnHand = 5,
        )
        net.attachToExternalSupplier(ihp, ConstantRV(0.25))
        val gen = net.attachDemandGenerator(
            ihp, item, ConstantRV(1.0), name = "DG",
            transportTime = ConstantRV.ZERO,
        )

        m.numberOfReplications = 1
        m.lengthOfReplication = 12.0
        m.simulate()

        val count = carrier.getNumberOfDemandShipments(ihp, gen)
        assert(count >= 10.0) { "expected >= 10 demand shipments on (P, DG) edge, got $count" }
    }
}
