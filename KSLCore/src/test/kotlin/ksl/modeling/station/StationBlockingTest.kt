package ksl.modeling.station

import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.ConstantRV
import kotlin.test.assertTrue

/**
 * Phase-3 (slice E) test for [BlockingStation]: a two-station line with a
 * single-slot downstream buffer forces block-after-service on the upstream server.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationBlockingTest {

    @Test
    fun blockAfterServiceOnFullDownstream() {
        val m = Model("Blocking", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val b2 = net.blockingStation("B2", bufferCapacity = 1, activityTime = ConstantRV(3.0), nextReceiver = exit)
        val b1 = net.blockingStation("B1", bufferCapacity = 10, activityTime = ConstantRV(1.0), nextReceiver = b2)
        net.source("Arrivals", ConstantRV(2.5), firstReceiver = b1, maxArrivals = 3)
        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0
        m.simulate()

        // B2 (service 3) is the bottleneck with a single slot; B1 finishes a job while
        // B2 is busy and blocks until B2 frees. Completions at t=6.5, 9.5, 12.5.
        // system times: 4, 4.5, 5 -> average 4.5
        assertEquals(3.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(4.5, net.systemTime.acrossReplicationStatistic.average, 1e-9)
        // B1 was blocked for part of the run (proves block-after-service occurred)
        assertTrue(b1.blockedProportion.acrossReplicationStatistic.average > 0.0, "B1 should experience blocking")
    }
}
