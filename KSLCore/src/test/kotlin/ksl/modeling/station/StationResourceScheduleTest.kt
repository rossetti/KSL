package ksl.modeling.station

import ksl.modeling.entity.CapacitySchedule
import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.ConstantRV

/**
 * Phase-2 (slice 1) tests for capacity schedules driving a station resource
 * through the [StationResourceIfc] seam. Deterministic constant service and
 * spaced arrivals make the off-shift / on-shift behavior exactly checkable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationResourceScheduleTest {

    @Test
    fun offShiftHoldsWorkThenResumes() {
        val m = Model("Shift1", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val server = net.singleQStation("Server", ConstantRV(1.0), nextReceiver = exit)
        val schedule = CapacitySchedule(m)
        schedule.addItem(capacity = 0, duration = 5.0)     // [0,5): off
        schedule.addItem(capacity = 1, duration = 995.0)   // [5, ...): on, capacity 1
        server.useCapacitySchedule(schedule)
        // arrivals at t = 1, 2, 3 — all during the off period, so all wait
        net.source("Arrivals", ConstantRV(1.0), firstReceiver = server, maxArrivals = 3)
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()

        // served sequentially from t=5: finishes at 6,7,8 -> system times 5,5,5
        assertEquals(3.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(5.0, net.systemTime.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun capacityIncreaseServesMultipleConcurrently() {
        val m = Model("Shift2", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val server = net.singleQStation("Server", ConstantRV(1.0), capacity = 2, nextReceiver = exit)
        val schedule = CapacitySchedule(m)
        schedule.addItem(capacity = 0, duration = 5.0)     // [0,5): off
        schedule.addItem(capacity = 2, duration = 995.0)   // [5, ...): on, capacity 2
        server.useCapacitySchedule(schedule)
        net.source("Arrivals", ConstantRV(1.0), firstReceiver = server, maxArrivals = 3)
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()

        // at t=5 capacity 2 -> serve two at once (finish 6), third served at 6 (finish 7)
        // system times: (6-1)=5, (6-2)=4, (7-3)=4 -> average 13/3
        assertEquals(3.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(13.0 / 3.0, net.systemTime.acrossReplicationStatistic.average, 1e-9)
    }
}
