package ksl.modeling.station

import ksl.modeling.entity.CapacitySchedule
import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import kotlin.test.assertTrue

/**
 * Phase-2 (slice 3c) tests for the unified resource state model
 * {idle, busy, failed, inactive} and schedule x failure composition.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationStateCompositionTest {

    @Test
    fun deterministicStatePartition() {
        val m = Model("StatePartition", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val server = net.singleQStation("Server", ConstantRV(2.0), nextReceiver = exit)
        val schedule = CapacitySchedule(m)
        schedule.addItem(capacity = 1, duration = 10.0)  // [0,10) on
        schedule.addItem(capacity = 0, duration = 5.0)   // [10,15) off
        schedule.addItem(capacity = 1, duration = 5.0)   // [15,20) on
        server.useCapacitySchedule(schedule)
        net.source("Arrivals", ConstantRV(1.0), firstReceiver = server, maxArrivals = 1)
        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0
        m.simulate()

        val r = server.resource
        // one job arrives t=1, served [1,3]; off-shift [10,15]
        //   idle = [0,1]+[3,10]+[15,20] = 13, busy = 2, inactive = 5, failed = 0  (of 20)
        assertEquals(0.10, r.busyStateProportion.acrossReplicationStatistic.average, 1e-9)
        assertEquals(0.25, r.inactiveStateProportion.acrossReplicationStatistic.average, 1e-9)
        assertEquals(0.65, r.idleStateProportion.acrossReplicationStatistic.average, 1e-9)
        assertEquals(0.0, r.failedStateProportion.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun scheduleAndFailuresCoexistAndPartition() {
        val m = Model("Coexist", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val server = net.singleQStation("Server", ExponentialRV(0.7, 2), nextReceiver = exit)
        // repeating shift: on 100, off 20
        val schedule = CapacitySchedule(m, repeatable = true)
        schedule.addItem(capacity = 1, duration = 100.0)
        schedule.addItem(capacity = 0, duration = 20.0)
        server.useCapacitySchedule(schedule)
        // and breakdowns
        server.useTimeBasedFailures(timeToFailure = ExponentialRV(50.0, 3), timeToRepair = ExponentialRV(10.0, 4))
        net.source("Arrivals", ExponentialRV(1.0, 1), firstReceiver = server)
        m.numberOfReplications = 20
        m.lengthOfReplication = 20000.0
        m.lengthOfReplicationWarmUp = 2000.0
        m.simulate()

        val r = server.resource
        val idle = r.idleStateProportion.acrossReplicationStatistic.average
        val busy = r.busyStateProportion.acrossReplicationStatistic.average
        val failed = r.failedStateProportion.acrossReplicationStatistic.average
        val inactive = r.inactiveStateProportion.acrossReplicationStatistic.average

        // the four states partition time
        assertEquals(1.0, idle + busy + failed + inactive, 1e-6)
        // schedule (inactive) and failures (failed) both occur, alongside busy/idle
        assertTrue(busy > 0.0, "busy=$busy")
        assertTrue(idle > 0.0, "idle=$idle")
        assertTrue(failed > 0.0, "failed=$failed")
        assertTrue(inactive > 0.0, "inactive=$inactive")
    }
}
