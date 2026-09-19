package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.TransporterState
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  What happens to the whole cast when a replication ends mid-transport.
 *
 *  This subsystem has four kinds of participant suspended at any moment: loads awaiting pickup,
 *  loads riding, vehicle agents dormant awaiting work, and the dispatcher dormant awaiting a wake.
 *  A vehicle's control loop is an unbounded `while (true)` that never returns on its own, and a
 *  horizon will routinely fall while all four are suspended.
 *
 *  **Nothing here cleans any of that up, and nothing should.** `ProcessModel.afterReplication`
 *  terminates every suspended entity -- including an agent parked inside an unbounded loop, by
 *  resuming its continuation with an exception that unwinds out of it -- and then releases its
 *  allocations, removes it from its queue with no statistics collected, and cancels any pending
 *  delay. `Queue.afterReplication` clears. The subsystem's entire obligation is to call `super` and
 *  to wake nobody.
 *
 *  That is exactly why this needs a test: there is no code in this package a maintainer could read
 *  that would tell them the property holds, and the one line that could break it -- an override of
 *  `afterReplication` that forgets `super` -- looks harmless.
 *
 *  The model is arranged so every horizon falls with all four hazards present, and the test asserts
 *  the hazards are **there** as well as that they are gone by the next replication. A cleanup test
 *  on a model that happens to finish its work proves nothing.
 */
class AgvReplicationCleanupTest {

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {

        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val cart = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(10.0), name = "Cart1"
        ).apply { homeBase = SimpleAgvNetwork.AGV1_HOME }

        val observations = mutableListOf<String>()

        inner class Part : Entity() {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                transportByFleet(agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION)
            }
        }

        private fun snapshot(phase: String) = "rep=${model.currentReplicationNumber} $phase " +
                "taskQ=${agv.dispatcher.taskQ.size} " +
                "awaiting=${agv.awaitingPickupHoldQ.size} " +
                "riding=${agv.inTransitHoldQ.size} " +
                "dormantVehicles=${agv.availabilityQ.size} " +
                "dispatcherIdle=${agv.dispatcherIdleQ.size} " +
                "movementQ=${agv.spaceSystem.drivingHoldQ.size} " +
                "cartBusy=${cart.transporter.numBusy} cartState=${cart.transporter.transporterState} " +
                "assigned=${cart.currentAssignment != null}"

        override fun initialize() {
            // Snapshot what the previous replication left behind, BEFORE creating this one's work.
            observations.add(snapshot("INIT"))
            // Six parts, one every five minutes. The horizon at t = 40 lands with one riding and
            // several still waiting to be collected.
            repeat(6) { i -> activate(Part().p, timeUntilActivation = i * 5.0) }
        }

        override fun replicationEnded() {
            super.replicationEnded()
            observations.add(snapshot("ENDED"))
        }
    }

    @Test
    @DisplayName("Every replication starts clean, however the previous one ended")
    fun everyReplicationStartsClean() {
        val m = Model("AgvCleanup")
        val shop = Shop(m)
        m.numberOfReplications = 4
        m.lengthOfReplication = 40.0
        m.simulate()

        val obs = shop.observations
        assertEquals(8, obs.size, "expected an INIT and an ENDED line per replication: $obs")

        for (rep in 1..4) {
            val ended = obs[2 * rep - 1]

            // The hazard is present. Without this the cleanup assertions below are vacuous.
            assertTrue(ended.contains("riding=1"),
                "replication $rep did not end with a load aboard, so it tests nothing: $ended")
            assertTrue(Regex("awaiting=[1-9]").containsMatchIn(ended),
                "replication $rep did not end with loads awaiting pickup: $ended")
            assertTrue(ended.contains("cartBusy=1"),
                "replication $rep did not end with the cart's body allocated: $ended")
            assertTrue(ended.contains("assigned=true"),
                "replication $rep did not end with the cart holding an assignment: $ended")
            assertTrue(ended.contains("movementQ=1"),
                "replication $rep did not end with the vehicle's agent under way: $ended")
            assertTrue(ended.contains("dispatcherIdle=1"),
                "replication $rep did not end with the dispatcher dormant: $ended")
        }

        // And every replication after the first begins with all of it gone -- queues empty, no
        // allocation, no assignment, the cart back at its initial placement and idle.
        for (rep in 2..4) {
            assertEquals(
                "rep=$rep INIT taskQ=0 awaiting=0 riding=0 dormantVehicles=0 dispatcherIdle=0 " +
                        "movementQ=0 cartBusy=0 cartState=IDLE assigned=false",
                obs[2 * rep - 2],
                "replication $rep did not start clean: $obs"
            )
        }

        // The horizon diagnostics report what was lost, rather than leaving it to be discovered.
        assertTrue(shop.agv.unfinishedTasksAtHorizon > 0,
            "the system did not report the tasks outstanding at the horizon")
        assertEquals(1, shop.agv.loadsInTransitAtHorizon)
        assertTrue(shop.agv.loadsAwaitingPickupAtHorizon > 0)

        // A wait that never completed is not an observation: the queue reports only the deliveries
        // that actually happened, not a zero for each abandoned one.
        val q = shop.agv.dispatcher.taskQ.timeInQ.withinReplicationStatistic
        assertTrue(q.count > 0.0, "no completed wait was ever recorded")
        assertTrue(q.count < 6.0, "every task completed, so the horizon is not being reached: ${q.count}")
    }
}
