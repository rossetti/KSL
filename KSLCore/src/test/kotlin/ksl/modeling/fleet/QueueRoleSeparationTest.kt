package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  The division of labour between the queue that reports and the queues that suspend.
 *
 *  `Conveyor` keeps these in different objects: typed request queues per entry location carry the
 *  statistics, and three hold queues carry the suspension with reporting switched off in the
 *  constructor. This subsystem does the same, and for the same reason -- a hold queue is how a
 *  suspended entity is found again and resumed, and letting it double as the statistic conflates a
 *  mechanism with a measurement.
 *
 *  The failure this guards against is not a crash. It is a summary report with five queue rows on
 *  it, four of them meaningless, one of which -- the in-transit queue -- looks exactly like a
 *  waiting line and would invite a utilization reading it cannot support. A later contributor
 *  noticing the "missing" rows and switching reporting on would be making the report worse while
 *  believing they were making it more complete.
 */
class QueueRoleSeparationTest {

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {
        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val cart = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(10.0), name = "Cart1"
        ).apply { homeBase = SimpleAgvNetwork.AGV1_HOME }

        inner class Part : Entity() {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                transportByFleet(agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION)
            }
        }

        override fun initialize() {
            activate(Part().p)
        }
    }

    /** All eight: the subsystem's five, plus the space layer's three movement queues -- of which
     *  the driving one holds vehicle agents rather than loads under this paradigm, and none of
     *  which must report either. */
    private fun holdQueueNames(shop: Shop) = listOf(
        shop.agv.awaitingPickupHoldQ, shop.agv.awaitingBoardingHoldQ, shop.agv.inTransitHoldQ,
        shop.agv.availabilityQ, shop.agv.dispatcherIdleQ, shop.agv.outOfServiceQ,
        shop.agv.spaceSystem.awaitingPickupHoldQ, shop.agv.spaceSystem.ridingHoldQ,
        shop.agv.spaceSystem.drivingHoldQ
    )

    @Test
    @DisplayName("By default the subsystem reports one queue: the dispatcher's task queue")
    fun holdQueuesReportNothingByDefault() {
        val m = Model("QueueRoles")
        val shop = Shop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 500.0
        m.simulate()

        for (q in holdQueueNames(shop)) {
            assertFalse(q.waitTimeStatOption, "${q.name} is collecting waiting time statistics")
            assertFalse(q.defaultReportingOption, "${q.name} appears on the summary report")
        }

        // The reported queue does report, and has something to say.
        val taskQ = shop.agv.dispatcher.taskQ
        assertTrue(taskQ.defaultReportingOption, "the task queue is not being reported")
        assertEquals(1.0, taskQ.timeInQ.withinReplicationStatistic.count,
            "the task queue recorded no completed wait")

        // The whole subsystem contributes exactly one queue row.
        val rows = m.simulationReporter.acrossReplicationStatisticsList()
            .map { it.name }
            .filter { it.startsWith("Agv") && (it.contains(":NumInQ") || it.contains(":TimeInQ")) }
        assertEquals(
            listOf("Agv:Dispatcher:TaskQ:NumInQ", "Agv:Dispatcher:TaskQ:TimeInQ").sorted(),
            rows.sorted(),
            "the summary report should carry exactly the task queue's rows and no hold queue's"
        )
    }

    @Test
    @DisplayName("Reporting can be switched on for debugging, as Conveyor allows")
    fun holdQueuesCanBeSwitchedOn() {
        val m = Model("QueueRolesOn")
        val shop = Shop(m)
        shop.agv.statisticalReportingForHoldQueues(true)
        m.numberOfReplications = 1
        m.lengthOfReplication = 500.0
        m.simulate()

        for (q in holdQueueNames(shop)) {
            assertTrue(q.waitTimeStatOption, "${q.name} did not switch on")
            assertTrue(q.defaultReportingOption, "${q.name} did not switch on")
        }

        val rows = m.simulationReporter.acrossReplicationStatisticsList()
            .map { it.name }
            .filter { it.startsWith("Agv") && it.contains(":NumInQ") }
        assertEquals(10, rows.size,
            "with reporting on, the task queue and all nine hold queues should appear: $rows")
    }
}
