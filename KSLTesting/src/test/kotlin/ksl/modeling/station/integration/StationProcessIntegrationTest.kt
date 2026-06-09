package ksl.modeling.station.integration

import ksl.modeling.station.StationNetwork
import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.ConstantRV

/**
 * Phase-3i (slice B) test for [ProcessStation]: a station whose activity is a
 * process-view process. The instance is sent onward when the process completes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationProcessIntegrationTest {

    @Test
    fun processStationRunsProcessAndContinues() {
        val m = Model("ProcessStation", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        // the activity is a full process body; here just a delay, but it could seize,
        // move, hold, etc. The instance continues to the sink when the process ends.
        val proc = net.processStation("Proc", nextReceiver = exit) { delay(2.0) }
        net.source("Arrivals", ConstantRV(10.0), firstReceiver = proc, maxArrivals = 3)
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        m.simulate()

        // each instance's carrier process delays 2.0 then continues -> system time 2.0
        assertEquals(3.0, proc.numProcessed.acrossReplicationStatistic.average, 1e-9)
        assertEquals(3.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(2.0, net.systemTime.acrossReplicationStatistic.average, 1e-9)
    }
}
