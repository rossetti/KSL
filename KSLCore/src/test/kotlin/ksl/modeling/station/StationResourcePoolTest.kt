package ksl.modeling.station

import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import kotlin.test.assertTrue

/**
 * Phase-2 (slice 4a) tests for a shared [SResourcePool] used by several
 * [ResourcePoolStation]s.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationResourcePoolTest {

    @Test
    fun sharedPoolSerializesWorkAcrossStations() {
        val m = Model("Pool", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val pool = net.resourcePool("Servers", capacity = 1)
        val a = net.resourcePoolStation("A", pool, ConstantRV(2.0), nextReceiver = exit)
        val b = net.resourcePoolStation("B", pool, ConstantRV(2.0), nextReceiver = exit)
        net.source("ArrA", ConstantRV(1.0), firstReceiver = a, maxArrivals = 1)
        net.source("ArrB", ConstantRV(1.0), firstReceiver = b, timeUntilFirstRV = ConstantRV(2.0), maxArrivals = 1)
        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0
        m.simulate()

        // single shared unit: cA served [1,3]; cB arrives t=2 but must wait for the
        // unit, served [3,5]. system times: cA=2, cB=3 -> average 2.5 (would be 2.0 if
        // each station had its own unit).
        assertEquals(2.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(2.5, net.systemTime.acrossReplicationStatistic.average, 1e-9)
        assertEquals(2, pool.numTimesSeized)
    }

    @Test
    fun poolViaDslRunsUnderLoad() {
        val m = Model("PoolDsl", autoCSVReports = false)
        lateinit var servers: SResourcePool
        val net = m.queueingNetwork("pool") {
            val exit = sink("Exit")
            servers = pool("Servers", capacity = 2)
            val s1 = pooledStation("S1", servers, ExponentialRV(1.5, 2), nextReceiver = exit)
            val s2 = pooledStation("S2", servers, ExponentialRV(1.5, 3), nextReceiver = exit)
            source("Arr1", ExponentialRV(2.0, 1)) routeTo s1
            source("Arr2", ExponentialRV(2.0, 4)) routeTo s2
        }
        m.numberOfReplications = 10
        m.lengthOfReplication = 20000.0
        m.lengthOfReplicationWarmUp = 2000.0
        m.simulate()

        assertTrue(net.numCompleted.acrossReplicationStatistic.average > 0.0)
        val util = servers.utilization.acrossReplicationStatistic.average
        assertTrue(util in 0.5..0.95, "pool utilization $util out of expected range")
    }
}
