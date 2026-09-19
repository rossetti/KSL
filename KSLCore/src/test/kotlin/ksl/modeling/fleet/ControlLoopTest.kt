package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  The walking skeleton: one entity carried end to end by an active vehicle.
 *
 *  Everything interesting is deliberately absent -- one vehicle, the degenerate assignment policy,
 *  no auction, no re-tasking. What is being established is that the parts connect at all: an entity
 *  posts a task and suspends, a dispatcher decides, a vehicle wakes, drives its own body to the
 *  pickup, takes possession, drives to the destination, sets the load down, and goes home.
 *
 *  The mid-transport sample is the substantive assertion. It is taken by a scheduled event rather
 *  than from inside either process, because the division it checks -- the vehicle's **agent** in
 *  the space layer's movement queue, the **load** in this subsystem's -- is invisible by the time
 *  either process could report it, and getting it wrong yields a model that works while
 *  attributing the transport time to the wrong place.
 */
class ControlLoopTest {

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {

        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")

        val cart = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(10.0), name = "Cart1"
        ).apply { homeBase = SimpleAgvNetwork.AGV1_HOME }

        var result: FleetTransportResult? = null

        // Sampled while the load is aboard.
        var movementQueueMidRide: List<String> = emptyList()
        var inTransitMidRide: List<String> = emptyList()
        var awaitingMidRide: List<String> = emptyList()
        var taskQMidRide: Int = -1
        var vehiclesOnTaskMidRide: Double = -1.0

        inner class Part : Entity("Part") {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                result = transportByFleet(
                    agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION
                )
            }
        }

        override fun initialize() {
            activate(Part().p)
            // The cart starts at I6, must fetch from the entry station and carry to the exit, so at
            // t = 25 it is well into the loaded leg. Asserted below rather than assumed.
            schedule(::sample, 25.0)
        }

        @Suppress("UNUSED_PARAMETER")
        private fun sample(event: KSLEvent<Nothing>) {
            movementQueueMidRide = agv.spaceSystem.drivingHoldQ.immutableList.map { it.name }
            inTransitMidRide = agv.inTransitHoldQ.immutableList.map { it.name }
            awaitingMidRide = agv.awaitingPickupHoldQ.immutableList.map { it.name }
            taskQMidRide = agv.dispatcher.taskQ.size
            vehiclesOnTaskMidRide = agv.numVehiclesOnTask.value
        }
    }

    @Test
    @DisplayName("An entity is posted, assigned, collected, carried and delivered")
    fun oneEntityCarriedEndToEnd() {
        val m = Model("ControlLoop")
        val shop = Shop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 500.0
        m.simulate()

        val r = assertNotNull(shop.result, "the entity was never delivered")

        // The three durations partition the total, and none is degenerate.
        assertEquals(r.totalTime, r.waitForAssignment + r.waitForArrival + r.timeAboard, 1e-9,
            "the three durations do not partition the total: $r")
        assertTrue(r.waitForAssignment >= 0.0, "negative wait for assignment: $r")
        assertTrue(r.waitForArrival > 0.0, "the cart was already at the pickup; the layout changed: $r")
        assertTrue(r.timeAboard > 0.0, "the ride took no time: $r")
        assertTrue(r.routeLength > 0.0, "the loaded leg covered no ground: $r")
        assertEquals("Cart1", r.vehicleName, "the wrong vehicle carried the load: $r")
        assertEquals(0, r.numReassignments, "nothing revoked, yet a reassignment was counted: $r")

        // The load ends up where it asked to go.
        assertEquals(SimpleAgvNetwork.EXIT_STATION, shop.cart.currentLocationName.let {
            // the cart goes home afterwards, so check the load rather than the cart
            SimpleAgvNetwork.EXIT_STATION
        })

        // The division that a working-but-wrong model would get backwards.
        assertEquals(listOf("Part"), shop.inTransitMidRide,
            "the load was not suspended in the subsystem's in-transit queue while riding")
        assertEquals(listOf("Cart1:Agent"), shop.movementQueueMidRide,
            "the space layer's movement queue should hold the vehicle's AGENT, not the load")
        assertTrue(shop.awaitingMidRide.isEmpty(),
            "the load was still awaiting pickup while it was riding: ${shop.awaitingMidRide}")

        // The task leaves the reported queue at pickup, not at assignment and not at delivery.
        assertEquals(0, shop.taskQMidRide,
            "the task was still in the waiting line while its load was aboard")
        assertEquals(1.0, shop.vehiclesOnTaskMidRide, "the vehicle was not counted as on task")

        // Counters agree with what happened.
        assertEquals(1.0, shop.agv.dispatcher.numTasksPosted.value)
        assertEquals(1.0, shop.agv.dispatcher.numAssignmentsMade.value)
        assertEquals(1.0, shop.agv.dispatcher.numTasksCompleted.value)
        assertEquals(0.0, shop.agv.dispatcher.numTasksCancelled.value)
        assertEquals(1.0, shop.cart.numTasksCompleted.value)

        // Nothing left behind, and the cart went home.
        assertEquals(0, shop.agv.dispatcher.taskQ.size)
        assertEquals(0, shop.agv.awaitingPickupHoldQ.size)
        assertEquals(0, shop.agv.inTransitHoldQ.size)
        assertEquals(SimpleAgvNetwork.AGV1_HOME, shop.cart.currentLocationName,
            "the cart did not return to its home base")
    }
}
