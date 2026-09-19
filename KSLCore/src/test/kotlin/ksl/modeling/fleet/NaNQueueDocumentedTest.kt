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
 *  A queue in which no wait ever completed reports a count of zero and an average of `NaN` -- not a
 *  zero average.
 *
 *  This is correct and it surprises people, which is why it is pinned: the guide says so, and a
 *  claim in a guide that nothing checks is a claim that goes stale.
 *
 *  The reasoning is sound. A wait that was still running when the horizon fell is not an observation
 *  of a wait; it is a wait whose length nobody knows. `afterTerminatedProcessCompletion` therefore
 *  dequeues a terminated entity with `waitStats = false`, so nothing is recorded. An average over
 *  zero observations is undefined, and `NaN` says so.
 *
 *  A zero would be far worse. It is a number, it looks like an answer, and it says the loads waited
 *  no time at all -- the most flattering possible reading of a fleet that in fact served nobody. A
 *  modeller comparing two configurations would prefer the one that failed completely.
 */
class NaNQueueDocumentedTest {

    private class NeverServed(parent: ModelElement) : ProcessModel(parent, "NeverServed") {
        val network = FeasibleSetTest.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        // On the island: one way in, no way out. It can reach nothing.
        val cart = AgvVehicle(
            agv, TransporterPlacement.At(FeasibleSetTest.ISLAND), ConstantRV(10.0), name = "Stranded"
        )

        inner class Part : Entity() {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(FeasibleSetTest.NORTH_STATION)
                transportByFleet(
                    agv, FeasibleSetTest.SOUTH_STATION, origin = FeasibleSetTest.NORTH_STATION
                )
            }
        }

        override fun initialize() {
            repeat(4) { i -> activate(Part().p, timeUntilActivation = i * 30.0) }
        }
    }

    @Test
    @DisplayName("No completed wait means count zero and average NaN, never a zero average")
    fun anUnservedQueueReportsNaNRatherThanZero() {
        val m = Model("NaNQueue")
        val shop = NeverServed(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()

        val timeInQ = shop.agv.dispatcher.taskQ.timeInQ.withinReplicationStatistic
        assertEquals(0.0, timeInQ.count, "no wait completed, so there should be no observations")
        assertTrue(timeInQ.weightedAverage.isNaN(),
            "an average over no observations should be NaN, not ${timeInQ.weightedAverage}. A zero " +
                    "is a number that looks like an answer, and it says these loads waited no time " +
                    "at all -- the most flattering possible reading of a fleet that served nobody.")

        // The same holds for the transport time, which observed nothing either.
        val transport = shop.agv.timeAboard.withinReplicationStatistic
        assertEquals(0.0, transport.count)
        assertTrue(transport.weightedAverage.isNaN(), "transport time should be NaN, not ${transport.weightedAverage}")

        // Meanwhile the number *in* the queue is a perfectly good time-weighted number: the loads
        // were there, and how many were waiting is known at every instant even though how long any
        // of them waited is not. The two statistics of one queue behave differently, and that is
        // right rather than inconsistent.
        val numInQ = shop.agv.dispatcher.taskQ.numInQ.withinReplicationStatistic
        assertTrue(numInQ.weightedAverage > 0.0,
            "the queue held loads throughout, so its time-weighted length is a real number: " +
                    "${numInQ.weightedAverage}")

        // And the diagnostics say what the NaN cannot: how much work was never done.
        assertEquals(4.0, shop.agv.numTasksNeverAssigned.value)
        assertEquals(4.0, shop.agv.numEntitiesNeverResumed.value)
    }
}
