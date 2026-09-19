package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.modeling.fleet.exceptions.FleetInvariantViolation
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathSpace
import ksl.modeling.guidedpath.GuidedPathTransportSystem
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  The closing audit, and the proof that it can fail.
 *
 *  An audit nobody has seen fail is not evidence of anything: it may be asserting something that
 *  cannot be false, or asserting it of a model it never reaches. So the substance of this class is
 *  the two negative controls -- a task taken off the board without being ended, and a load left
 *  suspended on work that no longer exists -- each of which is a mistake a modeller can make with
 *  the public API, and each of which the audit names.
 *
 *  The other half matters as much and is easier to overlook. An audit that raised on any run ending
 *  mid-delivery would be worse than none, because every busy model ends mid-delivery and the audit
 *  would be switched off within a week. The first test below is a saturated shop cut off at the
 *  horizon with loads queued, a load aboard and a vehicle part-way through a tour, and it passes:
 *  the audit checks that the parties agree about the state, not that there is no state left.
 */
class ClosingAuditTest {

    /** A ring, one cart, and more work than it can finish before the horizon. */
    private class Shop(parent: ModelElement, private val numLoads: Int = 6) :
        ProcessModel(parent, "Shop") {

        val network = AgvTestNetworks.ring()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")

        val cart = AgvVehicle(
            agv, TransporterPlacement.At(AgvTestNetworks.DEPOT), ConstantRV(10.0), name = "Cart"
        ).apply { homeBase = AgvTestNetworks.DEPOT }

        var delivered: Int = 0

        inner class Load(name: String) : Entity(name) {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(AgvTestNetworks.NEAR)
                transportByFleet(agv, AgvTestNetworks.DROP, origin = AgvTestNetworks.NEAR)
                delivered++
            }
        }

        override fun initialize() {
            delivered = 0
            for (i in 1..numLoads) activate(Load("Load$i").p, timeUntilActivation = i * 5.0)
        }
    }

    @Test
    @DisplayName("A replication cut off mid-delivery passes: the audit checks agreement, not tidiness")
    fun aBusyHorizonPasses() {
        val m = Model("BusyHorizon")
        val shop = Shop(m)
        m.numberOfReplications = 3
        // Long enough for a cart to be part way through a tour, far too short for six loads.
        m.lengthOfReplication = 120.0
        m.simulate()

        // The point of the test is that simulate() returned at all. These assertions establish that
        // it returned from the situation claimed -- a horizon that fell in the middle of the work,
        // not one that arrived after everything had quietly finished.
        assertTrue(shop.delivered < 6, "the shop finished its work, so nothing was cut off")
        assertTrue(
            shop.agv.numTasksNeverAssigned.acrossReplicationStatistic.average > 0.0,
            "no task was left unassigned, so the horizon did not fall mid-stream"
        )
    }

    /**
     *  A task taken off the board without being ended.
     *
     *  `Queue.remove` is public, so this is reachable from a model, and it is the shape of a real
     *  mistake: a modeller reaching past `removeAndTerminate` to drop work that is no longer wanted.
     *  A service task is used rather than a transport one so that the shortfall is in the task
     *  accounts alone, with no suspended load to confuse which check fired.
     */
    private class DroppedServiceTask(parent: ModelElement) : ProcessModel(parent, "Dropped") {

        val network = AgvTestNetworks.ring()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")

        @Suppress("unused")
        val cart = AgvVehicle(
            agv, TransporterPlacement.At(AgvTestNetworks.DEPOT), ConstantRV(10.0), name = "Cart"
        ).apply { homeBase = AgvTestNetworks.DEPOT }

        override fun initialize() {
            schedule(::postThenDrop, 5.0)
        }

        @Suppress("UNUSED_PARAMETER")
        private fun postThenDrop(event: KSLEvent<Nothing>) {
            val task = agv.dispatcher.postService(
                AgvTestNetworks.FAR, ServiceKind.Reposition, priority = 1
            )
            // Off the board, but never completed and never cancelled. Nothing raises here.
            (agv.dispatcher.taskQ as TaskQ).remove(task, false)
        }
    }

    @Test
    @DisplayName("A task dropped from the board without being ended is caught, and named")
    fun aDroppedTaskIsCaught() {
        val m = Model("DroppedTask")
        DroppedServiceTask(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 60.0

        val e = assertFailsWith<FleetInvariantViolation> { m.simulate() }
        val msg = e.message ?: ""
        assertTrue(msg.contains("went missing"), "the audit should say what is unaccounted for: $msg")
        assertTrue(msg.contains("posted"), "the audit should say how many were posted: $msg")
    }

    @Test
    @DisplayName("Switching the audit off lets the same broken model run to the end")
    fun theAuditCanBeSwitchedOff() {
        val m = Model("DroppedTaskUnaudited")
        val shop = DroppedServiceTask(m)
        shop.agv.auditAtReplicationEnd = false
        m.numberOfReplications = 1
        m.lengthOfReplication = 60.0

        // Completes, reports nothing, and is wrong. Which is what the audit exists to stop being
        // the default outcome -- and why this test is the one that proves the previous one was the
        // audit talking and not some other failure.
        m.simulate()
        assertEquals(1.0, shop.agv.dispatcher.numTasksPosted.value)
        assertEquals(0.0, shop.agv.dispatcher.numTasksCompleted.value)
        assertEquals(0.0, shop.agv.dispatcher.numTasksCancelled.value)
    }

    /**
     *  A transport task dropped from the board leaves its load suspended on nothing.
     *
     *  It has to be a task no vehicle has taken yet, and finding that out is itself worth recording:
     *  dropping an *assigned* task from the queue is harmless, because the commitment lives on the
     *  vehicle rather than in the queue and the delivery goes through regardless. So there are two
     *  loads and one cart here, and the one dropped is the one still waiting for a vehicle.
     */
    private class DroppedTransportTask(parent: ModelElement) : ProcessModel(parent, "DroppedLoad") {

        val network = AgvTestNetworks.ring()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")

        @Suppress("unused")
        val cart = AgvVehicle(
            agv, TransporterPlacement.At(AgvTestNetworks.DEPOT), ConstantRV(10.0), name = "Cart"
        ).apply { homeBase = AgvTestNetworks.DEPOT }

        inner class Load(name: String) : Entity(name) {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(AgvTestNetworks.FAR)
                transportByFleet(agv, AgvTestNetworks.DROP, origin = AgvTestNetworks.FAR)
            }
        }

        override fun initialize() {
            activate(Load("Carried").p)
            activate(Load("Orphan").p)
            schedule(::dropIt, 1.0)
        }

        @Suppress("UNUSED_PARAMETER")
        private fun dropIt(event: KSLEvent<Nothing>) {
            // The one still waiting for a vehicle: off the board, and now nothing in the model is
            // coming for it. Its load stays suspended in the hold queue for the rest of the run.
            val task = agv.dispatcher.board.tasks.firstOrNull { it.state == TaskState.POSTED } ?: return
            (agv.dispatcher.taskQ as TaskQ).remove(task, false)
        }
    }

    @Test
    @DisplayName("A load left suspended on a task that no longer exists is caught, and named")
    fun anOrphanedLoadIsCaught() {
        val m = Model("OrphanedLoad")
        DroppedTransportTask(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 60.0

        val e = assertFailsWith<FleetInvariantViolation> { m.simulate() }
        val msg = e.message ?: ""
        assertTrue(msg.contains("Orphan"), "the audit should name the load: $msg")
        assertTrue(
            msg.contains("nothing will ever wake it"),
            "the audit should say what is wrong rather than that something is: $msg"
        )
    }

    @Test
    @DisplayName("An active model can switch space checking on, and does so by default under the suite")
    fun checkingReachesTheSpaceLayer() {
        val m = Model("Reachable")
        val shop = Shop(m)

        // The build sets the property for every test JVM, so an active model is checked without
        // anything in this class asking for it. That is the coverage this control exists to give:
        // before it, no test of this subsystem ran under the space checker at all.
        assertTrue(
            GuidedPathSpace.defaultCheckInvariants(),
            "the suite is expected to run with ${GuidedPathSpace.CHECK_INVARIANTS_PROPERTY} set"
        )
        assertTrue(shop.agv.checkInvariants, "an active model did not inherit the suite's default")

        shop.agv.checkInvariants = false
        assertFalse(
            shop.agv.spaceSystem.checkInvariants,
            "switching checking off on the AGV system did not reach the guide path underneath"
        )
        shop.agv.checkInvariants = true
        assertTrue(shop.agv.spaceSystem.checkInvariants)

        // And the audit control reaches it too, so one switch covers both halves of the model.
        shop.agv.auditAtReplicationEnd = false
        assertFalse(shop.agv.spaceSystem.auditAtReplicationEnd)
    }
}
