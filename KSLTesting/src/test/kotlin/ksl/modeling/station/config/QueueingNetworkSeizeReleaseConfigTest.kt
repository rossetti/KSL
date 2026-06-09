package ksl.modeling.station.config

import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.RVData
import kotlin.test.assertTrue

/**
 * Phase-4 DTO/TOML coverage for seize/release: purely structural (resources,
 * seize stations referencing them, release stations referencing them). No hooks
 * required — the entity-side allocation registry is the network's, not user
 * code's.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueueingNetworkSeizeReleaseConfigTest {

    private fun const(x: Double) = RVData(RVType.Constant, mapOf("value" to doubleArrayOf(x)))

    /**
     * Nested seize of two cap-1 resources around a 5-unit inner delay; two entities;
     * mirrors StationSeizeReleaseTest.nestedSeizeHoldsBothResourcesAcrossDelay so the
     * DTO build is checked against the same deterministic outcome (system time avg = 7).
     */
    private fun nestedSeizeSpec() = QueueingNetworkSpec(
        name = "nested",
        sources = listOf(
            SourceSpec("Arrivals", const(1.0), maxArrivals = 2, routing = RoutingSpec.Direct("SeizeTester"))
        ),
        stations = listOf(
            // an ActivityStation isn't a SingleQStation; the DTO models pure-delay via
            // a singleQStation with no resource contention -- capacity 1 with zero
            // queue pressure (one customer at a time enters this delay since the
            // tester resource cap-1 serializes everything anyway)
            StationSpec("InnerDelay", const(5.0), capacity = 1, routing = RoutingSpec.Direct("ReleaseMachine"))
        ),
        sinks = listOf(SinkSpec("Exit")),
        resources = listOf(
            ResourceSpec("Tester", capacity = 1),
            ResourceSpec("Machine", capacity = 1)
        ),
        seizeStations = listOf(
            SeizeStationSpec("SeizeTester", resource = "Tester", routing = RoutingSpec.Direct("SeizeMachine")),
            SeizeStationSpec("SeizeMachine", resource = "Machine", routing = RoutingSpec.Direct("InnerDelay"))
        ),
        releaseStations = listOf(
            ReleaseStationSpec("ReleaseMachine", resource = "Machine", routing = RoutingSpec.Direct("ReleaseTester")),
            ReleaseStationSpec("ReleaseTester", resource = "Tester", routing = RoutingSpec.Direct("Exit"))
        )
    )

    @Test
    fun tomlRoundTripStable() {
        val s = nestedSeizeSpec()
        val t1 = QueueingNetworkToml.encode(s)
        val t2 = QueueingNetworkToml.encode(QueueingNetworkToml.decode(t1))
        assertEquals(t1, t2, "seize/release TOML re-encode should be stable")
        assertTrue(t1.contains("resources"))
        assertTrue(t1.contains("seizeStations"))
        assertTrue(t1.contains("releaseStations"))
        assertTrue(t1.contains("Tester"))
    }

    @Test
    fun dtoBuildsAndSimulatesNestedSeize() {
        val m = Model("DtoNestedSeize", autoCSVReports = false)
        val net = StationNetworkBuilder(nestedSeizeSpec()).build(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()

        // mirror StationSeizeReleaseTest.nestedSeizeHoldsBothResourcesAcrossDelay:
        //   c1 system time 5, c2 waits for c1's whole sequence (system time 9), avg 7
        assertEquals(2.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(7.0, net.systemTime.acrossReplicationStatistic.average, 1e-9)
        // no leaks at end of run
        assertEquals(0.0, net.holdingsAtRunEnd.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun unknownResourceFailsLoudly() {
        val bad = QueueingNetworkSpec(
            name = "bad",
            sources = listOf(SourceSpec("Arrivals", const(1.0), routing = RoutingSpec.Direct("S"))),
            sinks = listOf(SinkSpec("Exit")),
            seizeStations = listOf(
                SeizeStationSpec("S", resource = "Nope", routing = RoutingSpec.Direct("Exit"))
            )
        )
        val m = Model("DtoBadResource", autoCSVReports = false)
        val thrown = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            StationNetworkBuilder(bad).build(m)
        }
        assertTrue(thrown.message!!.contains("Nope"), "error should name the missing resource: ${thrown.message}")
    }
}
