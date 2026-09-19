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
import kotlin.test.assertTrue

/**
 *  What each of the three durations actually measures.
 *
 *  The subsystem reports one queue and two responses, and they measure disjoint intervals: the task
 *  queue runs from posting to pickup, `waitForAssignment` from posting to the moment a vehicle
 *  committed, and `timeAboard` from pickup to delivery. None can be derived from another, which
 *  is the reason all three are measured rather than two being computed from the third.
 *
 *  That makes them exactly the kind of thing that silently stops agreeing. The run is arranged so
 *  all three differ -- two parts and one cart, so the second part waits for assignment as well as
 *  for arrival -- and every figure is checked against the instants the parts observed for
 *  themselves, rather than against another figure this subsystem produced.
 */
class TaskQueueSemanticsTest {

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {

        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")

        val cart = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(10.0), name = "Cart1"
        ).apply { homeBase = SimpleAgvNetwork.AGV1_HOME }

        /** Per part: the task, what the verb reported, and the instants the part itself saw. */
        class Trace(
            val task: Dispatcher.TransportTask,
            val result: FleetTransportResult,
            val postedAt: Double,
            val deliveredAt: Double,
            val timeInQueueAfterPickup: Double
        )

        val traces = mutableListOf<Trace>()

        inner class Part(private val startAt: Double) : Entity("Part@$startAt") {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                val posted = time
                val task = requestFleetTransport(
                    agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION
                )
                val r = awaitFleetTransport(task)
                // Read after delivery. The task left the queue at pickup, so its time in queue is
                // frozen at that instant and is still readable here.
                traces.add(Trace(task, r, posted, time, task.timeInQueue))
            }
        }

        override fun initialize() {
            // Both parts arrive at once, and there is one cart, so the second waits for the first
            // to be delivered before it is even assigned. That is what makes waitForAssignment
            // differ from zero for one of them and not the other.
            activate(Part(0.0).p)
            activate(Part(0.0).p)
        }
    }

    @Test
    @DisplayName("The queue measures posting to pickup; the two responses measure either side of it")
    fun theThreeDurationsMeasureDisjointIntervals() {
        val m = Model("TaskQueueSemantics")
        val shop = Shop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 1000.0
        m.simulate()

        assertEquals(2, shop.traces.size, "both parts should have been delivered")

        val first = shop.traces[0]
        val second = shop.traces[1]

        // The run is only informative if the two parts had genuinely different experiences.
        assertEquals(0.0, first.result.waitForAssignment, 1e-9,
            "the first part should be assigned the instant it posts, with an idle cart waiting")
        assertTrue(second.result.waitForAssignment > 0.0,
            "the second part should have waited for the cart to finish: ${second.result}")

        for (t in shop.traces) {
            val r = t.result
            val label = "${t.task.name} -> $r"

            // The three partition the whole, against instants the PART observed, not against each
            // other. A decomposition checked only against its own total is checked against nothing.
            assertEquals(t.deliveredAt - t.postedAt, r.totalTime, 1e-9,
                "totalTime is not posting to delivery: $label")
            assertEquals(r.totalTime, r.waitForAssignment + r.waitForArrival + r.timeAboard, 1e-9,
                "the three durations do not partition the total: $label")

            // The queue measures posting to PICKUP -- not to assignment, and not to delivery. This
            // is the assertion that catches dequeuing the task in the wrong place, which would
            // still produce a plausible-looking number.
            assertEquals(r.waitForAssignment + r.waitForArrival, t.timeInQueueAfterPickup, 1e-9,
                "the task's time in queue is not the wait from posting to pickup: $label")
            assertTrue(t.timeInQueueAfterPickup < r.totalTime,
                "the task's wait should end at pickup, before delivery: $label")

            // Each duration is where the model says it is.
            assertEquals(t.task.assignedAt - t.postedAt, r.waitForAssignment, 1e-9,
                "waitForAssignment does not end when the vehicle committed: $label")
        }

        // The system's transport-time response saw exactly the two rides, and nothing else.
        val tt = shop.agv.timeAboard.withinReplicationStatistic
        assertEquals(2.0, tt.count, "the transport time response should have one observation per ride")
        assertEquals(
            (first.result.timeAboard + second.result.timeAboard) / 2.0,
            tt.weightedAverage, 1e-9,
            "the transport time response disagrees with what the verbs reported"
        )

        // The queue's own statistic agrees with the per-task figures it produced.
        val q = shop.agv.dispatcher.taskQ.timeInQ.withinReplicationStatistic
        assertEquals(2.0, q.count, "the task queue should have one completed wait per task")
        assertEquals(
            (first.timeInQueueAfterPickup + second.timeInQueueAfterPickup) / 2.0,
            q.weightedAverage, 1e-9,
            "the queue's average wait disagrees with the waits of the tasks that passed through it"
        )
    }
}
