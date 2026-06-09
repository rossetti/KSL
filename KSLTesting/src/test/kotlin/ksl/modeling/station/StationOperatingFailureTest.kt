package ksl.modeling.station

import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import kotlin.test.assertTrue

/**
 * Phase-2 (slice 3a) tests for the operating-time (usage) failure clock, which
 * advances only while the resource is busy and pauses while idle/off-shift/failed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationOperatingFailureTest {

    @Test
    fun operatingClockCountsOnlyBusyTime() {
        val m = Model("OpClock", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val server = net.singleQStation("Server", ConstantRV(2.0), nextReceiver = exit)
        // fail after 3 units of BUSY time; repair 5
        server.useOperatingTimeBasedFailures(operatingTimeToFailure = ConstantRV(3.0), timeToRepair = ConstantRV(5.0))
        // isolate the operating-clock behavior from the (default preempt-resume) effect
        server.useFinishThenFailEffect()
        // arrivals at t = 3, 6, 9 (spaced beyond the 2.0 service to create idle gaps)
        net.source("Arrivals", ConstantRV(3.0), firstReceiver = server, maxArrivals = 3)
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()

        // Busy intervals before failure: [3,5] accrues 2, idle [5,6], [6,7] accrues 1 -> total 3
        //   -> operating failure fires at t=7 (finish-then-fail). c2 finishes at 8, then down to 13.
        //   c3 (arrives 9) waits, served at 13, finishes 15.
        // A CALENDAR clock would instead have fired at t = 3+3 = 6; the t=7 timing proves the
        // idle gap [5,6] paused the clock.
        // system times: c1=2, c2=2, c3=6 -> average 10/3; one failure; failed during [8,13] = 5/50
        assertEquals(3.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(1.0, server.resource.numTimesFailed.acrossReplicationStatistic.average, 1e-9)
        assertEquals(10.0 / 3.0, net.systemTime.acrossReplicationStatistic.average, 1e-9)
        assertEquals(5.0 / 50.0, server.resource.failedStateProportion.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun operatingFailuresOccurUnderLoad() {
        val m = Model("OpClockLoad", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val server = net.singleQStation("Server", ExponentialRV(0.8, 2), nextReceiver = exit)
        server.useOperatingTimeBasedFailures(
            operatingTimeToFailure = ExponentialRV(50.0, 3),
            timeToRepair = ExponentialRV(10.0, 4)
        )
        net.source("Arrivals", ExponentialRV(1.0, 1), firstReceiver = server)
        m.numberOfReplications = 10
        m.lengthOfReplication = 50000.0
        m.lengthOfReplicationWarmUp = 5000.0
        m.simulate()

        assertTrue(server.resource.numTimesFailed.acrossReplicationStatistic.average > 0.0)
        // failed fraction is positive and well below 1
        val f = server.resource.failedStateProportion.acrossReplicationStatistic.average
        assertTrue(f in 0.01..0.5, "unexpected failed fraction $f")
    }
}
