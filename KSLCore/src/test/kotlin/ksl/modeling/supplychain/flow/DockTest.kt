package ksl.modeling.supplychain.flow

import ksl.modeling.supplychain.DemandCarrierIfc
import ksl.modeling.supplychain.DemandFillerAbstract
import ksl.modeling.supplychain.DemandFillerFinderIfc
import ksl.modeling.supplychain.DemandFillerIfc
import ksl.modeling.supplychain.DemandMessageIfc
import ksl.modeling.supplychain.DemandSenderIfc
import ksl.modeling.supplychain.DemandStateId
import ksl.modeling.supplychain.DemandStatusCode
import ksl.modeling.supplychain.ItemType
import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.inventory.NetworkNodeIfc

import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for [Dock] (Phase 1.E) — the default discrete-event
 * delivery endpoint that interposes a service time between
 * Delivered and the terminal action (store or re-ship).
 */
class DockTest {

    private class NoOpFiller(parent: ModelElement, name: String) :
        DemandFillerAbstract(parent, name = name) {
        override fun receive(demand: SupplyChainModel.Demand) {}
        override fun fillDemand(demand: SupplyChainModel.Demand) {}
        override fun negotiate(demand: SupplyChainModel.Demand): DemandMessageIfc? = null
        override fun canFillItemType(demand: SupplyChainModel.Demand) = true
        override fun canFillItemType(type: ItemType) = true
        override val itemTypes: Collection<ItemType> = emptyList()
        override fun determineRequestStatus(demand: SupplyChainModel.Demand) =
            DemandStatusCode.NoStatus
        override fun willReject(demand: SupplyChainModel.Demand) = false
    }

    /**
     * Minimal NetworkNodeIfc whose `deliveryEndpoint` is the
     * dock-under-test.  Acts as the demand's sender so the
     * framework dispatch hook routes to the dock.
     */
    private class TestNode(
        parent: ModelElement,
        name: String,
        override var deliveryEndpoint: DeliveryEndpointIfc,
    ) : DemandFillerAbstract(parent, name = name),
        DemandSenderIfc, NetworkNodeIfc {
        override fun receive(demand: SupplyChainModel.Demand) {}
        override fun fillDemand(demand: SupplyChainModel.Demand) {}
        override fun negotiate(demand: SupplyChainModel.Demand): DemandMessageIfc? = null
        override fun canFillItemType(demand: SupplyChainModel.Demand) = true
        override fun canFillItemType(type: ItemType) = true
        override val itemTypes: Collection<ItemType> = emptyList()
        override fun determineRequestStatus(demand: SupplyChainModel.Demand) =
            DemandStatusCode.NoStatus
        override fun willReject(demand: SupplyChainModel.Demand) = false
        override fun mightRequest(type: ItemType): Boolean = false
        override var demandFillerFinder: DemandFillerFinderIfc? = null
        override var demandFiller: DemandFillerIfc? = null
        override var level: Int = 0
    }

    @Test
    fun `zero service time dock stores immediately like the default endpoint`() {
        val m = Model("Dock.Zero")
        val sc = SupplyChainModel(m, name = "SC")
        val filler = NoOpFiller(sc, "F")
        // We need a NetworkNodeIfc-typed sender so the framework
        // dispatch hook routes to the dock.  Construct the dock,
        // then the node holding it as its delivery endpoint.
        lateinit var dock: Dock
        val node = TestNode(sc, "Dest", PassThroughStorageEndpoint)
        dock = Dock(node, serviceTime = ConstantRV.ZERO, name = "Dock")
        node.deliveryEndpoint = dock

        val item = ItemType(sc, name = "A")
        lateinit var observedDemand: SupplyChainModel.Demand
        object : ModelElement(sc, "Driver") {
            override fun initialize() {
                super.initialize()
                val d = sc.createDemand(item, 1)
                observedDemand = d
                d.setDemandSender(node)
                d.setFiller(filler)
                d.sent()
                d.receive(filler)
                d.process(filler)
                d.fill(1)
                d.ship()
                d.deliver()
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 1.0
        m.simulate()
        assertEquals(DemandStateId.Stored, observedDemand.demandState.stateId,
            "demand should be Stored after zero-time dock service")
        assertEquals(1.0, dock.numberServedResponse.value)
    }

    @Test
    fun `nonzero service time delays the store transition`() {
        val m = Model("Dock.Delay")
        val sc = SupplyChainModel(m, name = "SC")
        val filler = NoOpFiller(sc, "F")
        lateinit var dock: Dock
        val node = TestNode(sc, "Dest", PassThroughStorageEndpoint)
        dock = Dock(node, serviceTime = ConstantRV(0.5), name = "Dock")
        node.deliveryEndpoint = dock

        val item = ItemType(sc, name = "A")
        var timeOfStoredCapture = Double.NaN
        var timeOfDeliveredCapture = Double.NaN
        lateinit var observedDemand: SupplyChainModel.Demand

        object : ModelElement(sc, "Driver") {
            override fun initialize() {
                super.initialize()
                val d = sc.createDemand(item, 1)
                observedDemand = d
                d.setDemandSender(node)
                d.setFiller(filler)
                d.observe(object : DemandLifecycleObserver {
                    override fun onDelivered(demand: SupplyChainModel.Demand) {
                        timeOfDeliveredCapture = time
                    }
                    override fun onStored(demand: SupplyChainModel.Demand) {
                        timeOfStoredCapture = time
                    }
                })
                d.sent()
                d.receive(filler)
                d.process(filler)
                d.fill(1)
                d.ship()
                d.deliver()   // arrives at dock at time 0
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 2.0
        m.simulate()
        assertEquals(0.0, timeOfDeliveredCapture, 1e-9)
        assertEquals(0.5, timeOfStoredCapture, 1e-9,
            "Stored should fire 0.5 time units after Delivered")
        assertEquals(0.5, observedDemand.timeStored - observedDemand.timeDelivered, 1e-9)
    }

    @Test
    fun `dock queues a second demand while busy`() {
        val m = Model("Dock.Queue")
        val sc = SupplyChainModel(m, name = "SC")
        val filler = NoOpFiller(sc, "F")
        val node = TestNode(sc, "Dest", PassThroughStorageEndpoint)
        val dock = Dock(node, serviceTime = ConstantRV(0.5), name = "Dock")
        node.deliveryEndpoint = dock

        val item = ItemType(sc, name = "A")
        var queueSizeWhenSecondArrived = -1

        object : ModelElement(sc, "Driver") {
            override fun initialize() {
                super.initialize()
                fun newAndDeliver(): SupplyChainModel.Demand {
                    val d = sc.createDemand(item, 1)
                    d.setDemandSender(node)
                    d.setFiller(filler)
                    d.sent(); d.receive(filler); d.process(filler); d.fill(1); d.ship()
                    d.deliver()
                    return d
                }
                // First demand at time 0 — starts service.
                newAndDeliver()
                // Second demand also at time 0 — should be queued.
                newAndDeliver()
                queueSizeWhenSecondArrived = dock.queueSize
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 2.0
        m.simulate()
        assertEquals(1, queueSizeWhenSecondArrived,
            "second demand should be queued behind the first")
        assertEquals(2.0, dock.numberServedResponse.value)
    }

    @Test
    fun `Ship terminal action transitions demand back to Shipped via the carrier`() {
        val m = Model("Dock.Ship")
        val sc = SupplyChainModel(m, name = "SC")
        val filler = NoOpFiller(sc, "F")

        // Capture the demand passed to the carrier.
        var carrierTook: SupplyChainModel.Demand? = null
        val carrier = object : DemandCarrierIfc {
            override fun transportDemand(demand: SupplyChainModel.Demand) {
                carrierTook = demand
            }
            override fun canShip(demand: SupplyChainModel.Demand): Boolean = true
        }

        // Fake routing table — always returns a single hop.
        val nextHopNode = TestNode(sc, "Hop", PassThroughStorageEndpoint)
        val routing = object : RoutingTableIfc {
            override fun nextHop(finalDestination: DemandSenderIfc?): NetworkNodeIfc? =
                nextHopNode
        }

        val node = TestNode(sc, "CD", PassThroughStorageEndpoint)
        val dock = Dock(
            node,
            serviceTime = ConstantRV(0.25),
            terminalAction = DockTerminalAction.Ship(routing, carrier),
            name = "Dock",
        )
        node.deliveryEndpoint = dock

        val item = ItemType(sc, name = "A")
        lateinit var observedDemand: SupplyChainModel.Demand
        object : ModelElement(sc, "Driver") {
            override fun initialize() {
                super.initialize()
                val d = sc.createDemand(item, 1)
                observedDemand = d
                d.setDemandSender(node)
                d.setFiller(filler)
                d.sent(); d.receive(filler); d.process(filler); d.fill(1); d.ship()
                d.deliver()
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 1.0
        m.simulate()
        // The carrier got the demand, and the demand transitioned
        // Delivered → Shipped before the carrier saw it.
        assertSame(observedDemand, carrierTook)
        // Demand reaches Shipped (the carrier is a no-op so it
        // doesn't take it further).
        assertEquals(DemandStateId.Shipped, observedDemand.demandState.stateId)
    }

    @Test
    fun `utilization rises while busy and drops back to zero on idle`() {
        val m = Model("Dock.Util")
        val sc = SupplyChainModel(m, name = "SC")
        val filler = NoOpFiller(sc, "F")
        val node = TestNode(sc, "Dest", PassThroughStorageEndpoint)
        val dock = Dock(node, serviceTime = ConstantRV(1.0), name = "Dock")
        node.deliveryEndpoint = dock

        val item = ItemType(sc, name = "A")
        object : ModelElement(sc, "Driver") {
            override fun initialize() {
                super.initialize()
                val d = sc.createDemand(item, 1)
                d.setDemandSender(node)
                d.setFiller(filler)
                d.sent(); d.receive(filler); d.process(filler); d.fill(1); d.ship()
                d.deliver()
                // Dock busy from time 0 to time 1; idle from time 1 onward.
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 2.0
        m.simulate()
        // Time-weighted utilisation = (1 unit busy + 1 unit idle) / 2
        // = 0.5
        val utilAvg = dock.utilizationResponse.withinReplicationStatistic.weightedAverage
        assertEquals(0.5, utilAvg, 1e-9)
    }
}
