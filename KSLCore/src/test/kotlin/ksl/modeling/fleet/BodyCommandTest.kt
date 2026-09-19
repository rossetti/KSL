package ksl.modeling.fleet

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.agent.AgentModel
import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.RequestQ
import ksl.modeling.guidedpath.GuidedPathTransportSystem
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.spatial.MovePurpose
import ksl.modeling.guidedpath.MovementWait
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.TransporterState
import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  V2 of the AGV plan's Phase 0. Verifies that an *active* vehicle can command a passive body.
 *
 *  The active design keeps the space layer exactly as the passive subsystem built it and changes
 *  only who decides. A vehicle agent seizes its own body, calls `beginJourney` passing **itself**
 *  as the waiting entity, and is resumed when the body arrives. Three things about that are worth
 *  pinning before anything is built on them.
 *
 *  **`sendTo` is not the way in.** It refuses while the body is allocated, which is correct for its
 *  own purpose -- a passive transporter that belongs to an entity must not wander off -- but it
 *  makes `sendTo` unusable for a vehicle that has deliberately seized its own body to record
 *  commitment (invariant `A12`). `beginJourney` is the entry point, and the refusal is asserted
 *  here so that a later contributor reaching for the public function finds out why it is not used.
 *
 *  **The agent is the waiter, never the load.** The space layer's movement hold queue holds
 *  whatever was passed to `beginJourney`. Pass the load instead and the model still works -- it
 *  arrives, it delivers, the run completes -- but the load then accrues its riding time in the
 *  space layer's queue rather than the subsystem's, and every transport statistic is attributed to
 *  the wrong place. Behaviour is exactly what would *not* reveal the mistake, so the assertion is
 *  on the two queues' contents, sampled mid-journey by an event outside either process.
 *
 *  **The load is suspended somewhere else entirely, and it is not the modeller's model.** The load
 *  belongs to an ordinary `ProcessModel` and waits in a `HoldQueue` owned by the `AgentModel`,
 *  which is the arrangement V1 established.
 */
class BodyCommandTest {

    private class Depot(parent: ModelElement) : AgentModel(parent, "Depot") {

        val network = SimpleAgvNetwork.create()
        val space = GuidedPathTransportSystem(this, network, name = "Space")

        val body = GuidedTransporter(
            space, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME),
            ConstantRV(10.0), 1, EndOfZoneControl(), "Cart"
        )

        /** Required by `seize`, which always takes a queue. Nothing ever waits here: the body is
         *  seized only by its own agent (`A12`). */
        val bodyQ = RequestQ(this, "Cart:BodyQ")

        /** Stands in for `inTransitHoldQ`: where the load waits while it is carried. */
        val loadHoldQ = HoldQueue(this, "Depot:InTransitHoldQ")

        val log = mutableListOf<String>()
        var sendToRefusal: String? = null
        var arrivedAt: Double = Double.NaN

        /** Sampled mid-journey by a scheduled event, because both queues are empty by the end. */
        var movementQueueMidJourney: List<String> = emptyList()
        var loadQueueMidJourney: List<String> = emptyList()

        /** Stands in for `VehicleAgent`: seizes its body and drives it. */
        inner class Driver(private val load: ProcessModel.Entity) : Agent("Driver") {
            val control = process(isDefaultProcess = true) {
                val allocation = seize(body, 1, queue = bodyQ)

                // The public repositioning function is now closed to us, and that is the point.
                sendToRefusal = try {
                    body.sendTo(SimpleAgvNetwork.EXIT_STATION)
                    null
                } catch (e: IllegalStateException) {
                    e.message
                }

                log.add("departing at $time")
                // Aboard before departing. A caller no longer asserts that a journey is loaded: it
                // says the journey is for service, and the body reads its own manifest. A driver
                // that forgot to board would report an empty move while carrying, which is exactly
                // the class of mistake the derivation removes.
                body.board(load)
                // `this@Driver` is the waiter: the AGENT sits in the space layer's movement queue,
                // and the load stays in ours.
                val travelQ = space.beginJourney(
                    body, SimpleAgvNetwork.EXIT_STATION, MovePurpose.SERVICE, this@Driver,
                    MovementWait.DRIVING
                )
                assertTrue(travelQ != null, "the body was already at the destination; the layout changed")
                hold(travelQ!!, suspensionName = "travelling")
                arrivedAt = time
                log.add("arrived at $time")

                body.alight(load)
                loadHoldQ.removeAndResume(load)
                release(allocation)
            }
        }

        @Suppress("UNUSED_PARAMETER")
        private fun sample(event: KSLEvent<Nothing>) {
            movementQueueMidJourney = space.drivingHoldQ.immutableList.map { it.name }
            loadQueueMidJourney = loadHoldQ.immutableList.map { it.name }
        }

        /** Called by the shop once its load is suspended, so the driver starts with a load to carry. */
        fun dispatch(load: ProcessModel.Entity) {
            activate(Driver(load).control)
            schedule(::sample, 1.0)
        }
    }

    /** The modeller's own model. Its entity is carried; it never touches the space layer. */
    private class Shop(parent: ModelElement, private val depot: Depot) : ProcessModel(parent, "Shop") {

        val log = mutableListOf<String>()

        inner class Load : Entity("Load") {
            val p = process(isDefaultProcess = true) {
                depot.dispatch(this@Load)
                hold(depot.loadHoldQ, suspensionName = "riding")
                log.add("delivered at $time")
            }
        }

        override fun initialize() {
            activate(Load().p)
        }
    }

    @Test
    @DisplayName("V2: an agent seizes its own body, drives it, and is the waiter -- not the load")
    fun agentDrivesItsOwnBody() {
        val m = Model("BodyCommand")
        val depot = Depot(m)
        val shop = Shop(m, depot)
        m.numberOfReplications = 1
        m.lengthOfReplication = 500.0
        m.simulate()

        // The agent resumed on arrival, so the journey completed and took simulated time.
        assertEquals(2, depot.log.size, "the driver did not complete its journey: ${depot.log}")
        assertTrue(depot.arrivedAt > 1.0,
            "the journey finished before the mid-journey sample at t=1.0 (arrived at ${depot.arrivedAt}), " +
                    "so the sample below proves nothing")
        assertEquals(listOf("delivered at ${depot.arrivedAt}"), shop.log,
            "the load was not resumed when the vehicle arrived")

        // `sendTo` refuses while the body is allocated. This is why beginJourney is the entry point.
        assertTrue(
            depot.sendToRefusal?.contains("is allocated and cannot be sent") == true,
            "sendTo did not refuse an allocated body; the reason for using beginJourney has changed: " +
                    "${depot.sendToRefusal}"
        )

        // The division that matters: the AGENT rides the space layer's queue, the LOAD waits in ours.
        assertEquals(listOf("Driver"), depot.movementQueueMidJourney,
            "the space layer's driving queue held the wrong waiter during the journey")
        assertEquals(listOf("Load"), depot.loadQueueMidJourney,
            "the load was not suspended in the subsystem's own queue during the journey")

        // Nothing left behind.
        assertEquals(0, depot.space.drivingHoldQ.size, "the driving queue was not emptied")
        assertEquals(0, depot.loadHoldQ.size, "the load was left in the hold queue")
        assertEquals(0, depot.body.numBusy, "the body was left allocated")
    }
}
