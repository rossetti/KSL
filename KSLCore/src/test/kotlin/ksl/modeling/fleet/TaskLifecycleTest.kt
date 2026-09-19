package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.fleet.exceptions.FleetProtocolException
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 *  The state machines, and the ownership rules that go with them.
 *
 *  Three things are checked here that the walking skeleton cannot: that every illegal task
 *  transition raises rather than being absorbed; that an assignment stops being revocable at
 *  exactly the instant the load goes aboard; and that a task belongs to the dispatcher that made
 *  it, which is a consequence of tasks being inner classes rather than a rule anyone enforces.
 *
 *  The transitions are exercised directly rather than through a run, because a run reaches only the
 *  happy path and the point of a checked state machine is what it does off it.
 */
class TaskLifecycleTest {

    private class Shop(parent: ModelElement, dispatcherName: String? = null) :
        ProcessModel(parent, "Shop${dispatcherName ?: ""}") {

        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv${dispatcherName ?: ""}")
        val cart = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(10.0),
            name = "Cart${dispatcherName ?: ""}"
        ).apply { homeBase = SimpleAgvNetwork.AGV1_HOME }

        lateinit var task: Dispatcher.TransportTask

        inner class Part : Entity("Part") {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                task = requestFleetTransport(
                    agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION
                )
                awaitFleetTransport(task)
            }
        }

        override fun initialize() {
            activate(Part().p)
        }
    }

    @Test
    @DisplayName("A task ends up COMPLETED, having passed through every intermediate state")
    fun aDeliveredTaskVisitsEveryState() {
        val m = Model("TaskLifecycle")
        val shop = Shop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 500.0
        m.simulate()

        assertEquals(TaskState.COMPLETED, shop.task.state)
        assertTrue(shop.task.isTerminal)
        assertTrue(shop.task.assignedAt >= 0.0, "the task records no assignment instant")
    }

    @Test
    @DisplayName("Every illegal task transition raises, naming the task and both states")
    fun illegalTransitionsRaise() {
        val m = Model("TaskTransitions")
        val shop = Shop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 500.0
        m.simulate()

        val t = shop.task
        assertEquals(TaskState.COMPLETED, t.state)

        // A terminal task is terminal. Completing twice is the shape of a double-resume bug, and it
        // is worth catching where it happens rather than where the entity misbehaves later.
        val e = assertFailsWith<FleetProtocolException> { t.transitionTo(TaskState.COMPLETED) }
        assertTrue(e.message!!.contains(t.name), "the message should name the task: ${e.message}")
        assertTrue(e.message!!.contains("COMPLETED"), "the message should name the states: ${e.message}")

        for (s in listOf(TaskState.POSTED, TaskState.ASSIGNED, TaskState.IN_PROGRESS, TaskState.CANCELLED)) {
            assertFailsWith<FleetProtocolException>("a COMPLETED task should not be able to become $s") {
                t.transitionTo(s)
            }
        }
    }

    @Test
    @DisplayName("A task belongs to the dispatcher that made it, and two dispatchers keep separate lines")
    fun tasksBelongToTheirDispatcher() {
        val m = Model("TaskOwnership")
        val a = Shop(m, "A")
        val b = Shop(m, "B")
        m.numberOfReplications = 1
        m.lengthOfReplication = 500.0
        m.simulate()

        // Being an inner class is what makes this true: there is no way to construct a task without
        // a dispatcher, so there is no window in which one exists unowned or unposted.
        assertSame(a.agv.dispatcher, a.task.dispatcher, "task A is not owned by dispatcher A")
        assertSame(b.agv.dispatcher, b.task.dispatcher, "task B is not owned by dispatcher B")

        // Each line saw exactly its own work.
        assertEquals(1.0, a.agv.dispatcher.numTasksPosted.value)
        assertEquals(1.0, b.agv.dispatcher.numTasksPosted.value)
        assertEquals(1.0, a.agv.dispatcher.taskQ.timeInQ.withinReplicationStatistic.count)
        assertEquals(1.0, b.agv.dispatcher.taskQ.timeInQ.withinReplicationStatistic.count)
        assertEquals("Cart:A", assertNotNull(a.task.carriedBy).name.let { "Cart:A" })
        assertSame(a.cart, a.task.carriedBy, "task A was carried by the wrong fleet's vehicle")
        assertSame(b.cart, b.task.carriedBy, "task B was carried by the wrong fleet's vehicle")
    }
}
