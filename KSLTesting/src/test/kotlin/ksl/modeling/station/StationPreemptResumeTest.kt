package ksl.modeling.station

import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.ConstantRV

/**
 * Phase-2 (slice 3b) tests for preempt-resume failures: an in-service job is
 * interrupted, its remaining time banked, and resumed (for exactly that time)
 * after repair.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationPreemptResumeTest {

    @Test
    fun preemptResumePreservesRemainingService() {
        val m = Model("Preempt", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val server = net.singleQStation("Server", ConstantRV(4.0), nextReceiver = exit)
        // calendar failures: fail every 2 wall-clock units, repair 3. Default effect is preempt-resume.
        server.useTimeBasedFailures(timeToFailure = ConstantRV(2.0), timeToRepair = ConstantRV(3.0))
        net.source("Arrivals", ConstantRV(1.0), firstReceiver = server, maxArrivals = 1)
        m.numberOfReplications = 1
        m.lengthOfReplication = 11.5
        m.simulate()

        // failure at t=2 schedules from t=0; c1 arrives t=1, service 4 (would end t=5)
        //  t=2 preempt: remaining 3, down to 5
        //  t=5 resume: remaining 3 (would end t=8); next failure scheduled t=7
        //  t=7 preempt: remaining 1, down to 10
        //  t=10 resume: remaining 1, ends t=11 -> completes
        // total service performed = 1 + 2 + 1 = 4 (== original); system time = 11 - 1 = 10
        assertEquals(1.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(2.0, server.resource.numTimesFailed.acrossReplicationStatistic.average, 1e-9)
        assertEquals(10.0, net.systemTime.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun finishThenFailDiffersFromPreempt() {
        // Same scenario but finish-then-fail: the job is NOT interrupted; the
        // failure waits until it completes. With service 4 starting at t=1 and the
        // first failure trigger at t=2, the job finishes at t=5, then the resource
        // goes down. The job's system time is just its service (4).
        val m = Model("FinishThenFail", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val server = net.singleQStation("Server", ConstantRV(4.0), nextReceiver = exit)
        server.useTimeBasedFailures(timeToFailure = ConstantRV(2.0), timeToRepair = ConstantRV(3.0))
        server.useFinishThenFailEffect()
        net.source("Arrivals", ConstantRV(1.0), firstReceiver = server, maxArrivals = 1)
        m.numberOfReplications = 1
        m.lengthOfReplication = 11.5
        m.simulate()

        assertEquals(1.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        // c1 completes at t=5 (not interrupted) -> system time 4
        assertEquals(4.0, net.systemTime.acrossReplicationStatistic.average, 1e-9)
    }
}
