package ksl.modeling.station

import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import kotlin.test.assertTrue

/**
 * Phase-2 (slice 2) tests for resource failures: count-based and calendar-time
 * triggers with finish-then-fail, full-down semantics.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationFailureTest {

    @Test
    fun countBasedFailureWhileIdle() {
        val m = Model("CountFail", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val server = net.singleQStation("Server", ConstantRV(1.0), nextReceiver = exit)
        server.useCountBasedFailures(countToFailure = ConstantRV(2.0), timeToRepair = ConstantRV(3.0))
        // spaced arrivals (10) >> service (1): no queue; failures occur while idle
        net.source("Arrivals", ConstantRV(10.0), firstReceiver = server, maxArrivals = 4)
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()

        // completions at 11,21,31,41; failures triggered at the 2nd and 4th completion
        // (t=21 -> repair to 24, t=41 -> repair to 44): 2 failures, all 4 completed
        assertEquals(4.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(2.0, server.resource.numTimesFailed.acrossReplicationStatistic.average, 1e-9)
        // failed during [21,24] and [41,44] = 6 of 50 time units
        assertEquals(6.0 / 50.0, server.resource.failedStateProportion.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun finishThenFailBlocksWaitingCustomer() {
        val m = Model("FinishThenFail", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val server = net.singleQStation("Server", ConstantRV(2.0), nextReceiver = exit)
        server.useCountBasedFailures(countToFailure = ConstantRV(1.0), timeToRepair = ConstantRV(5.0))
        net.source("Arrivals", ConstantRV(1.0), firstReceiver = server, maxArrivals = 2)
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()

        // c1: arrive 1, serve, finish 3 -> fail (every completion fails), repair to 8
        // c2: arrive 2, waits through the repair, served at 8, finish 10 -> fail again
        // system times: c1 = 2, c2 = 8 -> average 5.0; two failures total
        assertEquals(2.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(2.0, server.resource.numTimesFailed.acrossReplicationStatistic.average, 1e-9)
        assertEquals(5.0, net.systemTime.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun timeBasedFailureAvailability() {
        val m = Model("TimeFail", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val server = net.singleQStation("Server", ExponentialRV(1.0, 2), nextReceiver = exit)
        // MTBF = 100, MTTR = 20 -> long-run failed fraction ~ 20/120 = 0.1667
        server.useTimeBasedFailures(timeToFailure = ExponentialRV(100.0, 3), timeToRepair = ExponentialRV(20.0, 4))
        // light load so failures mostly occur while idle (clean availability)
        net.source("Arrivals", ExponentialRV(50.0, 1), firstReceiver = server)
        m.numberOfReplications = 30
        m.lengthOfReplication = 100000.0
        m.lengthOfReplicationWarmUp = 10000.0
        m.simulate()

        assertTrue(server.resource.numTimesFailed.acrossReplicationStatistic.average > 0.0)
        val failedFraction = server.resource.failedStateProportion.acrossReplicationStatistic.average
        assertTrue(failedFraction in 0.137..0.197, "failed fraction $failedFraction not near 20/120")
    }
}
