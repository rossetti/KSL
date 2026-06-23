package ksl.modeling.station

import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.ConstantRV

/**
 * Phase-2 (slice 4b) tests for sequence-dependent setup (changeover) times.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationSetupTest {

    @Test
    fun changeoverSetupOnlyOnTypeChange() {
        val m = Model("Setup", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val server = net.singleQStation("Server", ConstantRV(2.0), nextReceiver = exit)
        server.setupTimeRule = ChangeoverSetupTime(1.0)
        // types in arrival order: 1, 1, 2, 2 (changeover at the 1st and 3rd)
        var count = 0
        net.source(
            "Arrivals", ConstantRV(10.0), firstReceiver = server, maxArrivals = 4,
            marking = { q -> q.qObjectType = if (count++ < 2) 1 else 2 }
        )
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        m.simulate()

        // arrivals spaced (10) >> service+setup, so no queueing:
        //   c1 type1: setup 1 + service 2 = 3
        //   c2 type1: setup 0 + service 2 = 2
        //   c3 type2: setup 1 + service 2 = 3
        //   c4 type2: setup 0 + service 2 = 2
        // system times average = (3+2+3+2)/4 = 2.5; mean setup per job = (1+0+1+0)/4 = 0.5
        assertEquals(4.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(2.5, net.systemTime.acrossReplicationStatistic.average, 1e-9)
        assertEquals(0.5, server.setupTimeResponse.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun sequenceDependentSetupMatrix() {
        val m = Model("SeqSetup", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val server = net.singleQStation("Server", ConstantRV(2.0), nextReceiver = exit)
        server.setupTimeRule = SequenceDependentSetupTime(
            setups = mapOf((1 to 2) to 5.0, (2 to 1) to 3.0),
            initialSetups = mapOf(1 to 2.0)
        )
        // types in arrival order: 1, 2, 1
        var count = 0
        val seq = intArrayOf(1, 2, 1)
        net.source(
            "Arrivals", ConstantRV(20.0), firstReceiver = server, maxArrivals = 3,
            marking = { q -> q.qObjectType = seq[count++] }
        )
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        m.simulate()

        // setups: initial(1)=2, (1->2)=5, (2->1)=3 -> mean (2+5+3)/3 = 10/3
        // service 2 each; spaced so no queue. system times: 4, 7, 5 -> average 16/3
        assertEquals(3.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(10.0 / 3.0, server.setupTimeResponse.acrossReplicationStatistic.average, 1e-9)
        assertEquals(16.0 / 3.0, net.systemTime.acrossReplicationStatistic.average, 1e-9)
    }
}
