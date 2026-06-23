package ksl.modeling.station.config

import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.RVData
import kotlin.test.assertTrue

/**
 * Phase-4 DTO/TOML coverage for the Phase-3 station archetypes: pools, pooled,
 * batch, separate, gate, blocking, NWay, and match stations. Multi-input stations
 * are addressed in routing with the "name#index" target syntax.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueueingNetworkPhase3ConfigTest {

    private fun const(x: Double) = RVData(RVType.Constant, mapOf("value" to doubleArrayOf(x)))

    @Test
    fun tomlRoundTripStableForPhase3Stations() {
        val spec = QueueingNetworkSpec(
            name = "phase3",
            sources = listOf(SourceSpec("Arrivals", const(1.0), routing = RoutingSpec.Direct("Batch"))),
            sinks = listOf(SinkSpec("Exit")),
            pools = listOf(PoolSpec("Servers", capacity = 2)),
            pooledStations = listOf(PooledStationSpec("Pooled", "Servers", const(2.0), RoutingSpec.Direct("Exit"))),
            batchStations = listOf(BatchStationSpec("Batch", 3, RoutingSpec.Direct("Separate"))),
            separateStations = listOf(SeparateStationSpec("Separate", RoutingSpec.Direct("Gate"))),
            gateStations = listOf(GateStationSpec("Gate", initiallyOpen = true, routing = RoutingSpec.Direct("Block"))),
            blockingStations = listOf(BlockingStationSpec("Block", 1, const(2.0), RoutingSpec.Direct("Exit"))),
            nWayStations = listOf(NWayStationSpec("NW", numQueues = 2, activityTime = const(2.0), selection = QueueSelection.ROUND_ROBIN, routing = RoutingSpec.Direct("Exit"))),
            matchStations = listOf(MatchStationSpec("MM", numInputs = 2, keyByType = true, routing = RoutingSpec.Direct("Exit")))
        )
        val t1 = QueueingNetworkToml.encode(spec)
        val t2 = QueueingNetworkToml.encode(QueueingNetworkToml.decode(t1))
        assertEquals(t1, t2, "Phase-3 TOML re-encode should be stable")
        assertTrue(t1.contains("batchStations"))
        assertTrue(t1.contains("blockingStations"))
        assertTrue(t1.contains("ROUND_ROBIN"))
    }

    @Test
    fun batchSeparateViaDto() {
        val spec = QueueingNetworkSpec(
            name = "batch",
            sources = listOf(SourceSpec("Arrivals", const(1.0), maxArrivals = 6, routing = RoutingSpec.Direct("Batch"))),
            batchStations = listOf(BatchStationSpec("Batch", 3, RoutingSpec.Direct("Separate"))),
            separateStations = listOf(SeparateStationSpec("Separate", RoutingSpec.Direct("Exit"))),
            sinks = listOf(SinkSpec("Exit"))
        )
        val m = Model("DtoBatch", autoCSVReports = false)
        val net = StationNetworkBuilder(spec).build(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()
        assertEquals(6.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun blockingLineViaDto() {
        val spec = QueueingNetworkSpec(
            name = "blocking",
            sources = listOf(SourceSpec("Arrivals", const(2.5), maxArrivals = 3, routing = RoutingSpec.Direct("B1"))),
            blockingStations = listOf(
                BlockingStationSpec("B1", bufferCapacity = 10, activityTime = const(1.0), routing = RoutingSpec.Direct("B2")),
                BlockingStationSpec("B2", bufferCapacity = 1, activityTime = const(3.0), routing = RoutingSpec.Direct("Exit"))
            ),
            sinks = listOf(SinkSpec("Exit"))
        )
        val m = Model("DtoBlocking", autoCSVReports = false)
        val net = StationNetworkBuilder(spec).build(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0
        m.simulate()
        assertEquals(3.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(4.5, net.systemTime.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun pooledStationsViaDto() {
        val spec = QueueingNetworkSpec(
            name = "pool",
            sources = listOf(
                SourceSpec("ArrA", const(1.0), maxArrivals = 1, routing = RoutingSpec.Direct("A")),
                SourceSpec("ArrB", const(1.0), timeUntilFirst = const(2.0), maxArrivals = 1, routing = RoutingSpec.Direct("B"))
            ),
            pools = listOf(PoolSpec("Servers", capacity = 1)),
            pooledStations = listOf(
                PooledStationSpec("A", "Servers", const(2.0), RoutingSpec.Direct("Exit")),
                PooledStationSpec("B", "Servers", const(2.0), RoutingSpec.Direct("Exit"))
            ),
            sinks = listOf(SinkSpec("Exit"))
        )
        val m = Model("DtoPool", autoCSVReports = false)
        val net = StationNetworkBuilder(spec).build(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0
        m.simulate()
        assertEquals(2.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(2.5, net.systemTime.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun nWayViaDtoWithInputTargeting() {
        val spec = QueueingNetworkSpec(
            name = "nway",
            sources = listOf(
                SourceSpec("ArrA", const(1.0), maxArrivals = 2, routing = RoutingSpec.Direct("NW#0")),
                SourceSpec("ArrB", const(1.0), timeUntilFirst = const(1.5), maxArrivals = 1, routing = RoutingSpec.Direct("NW#1"))
            ),
            nWayStations = listOf(NWayStationSpec("NW", numQueues = 2, activityTime = const(2.0), routing = RoutingSpec.Direct("Exit"))),
            sinks = listOf(SinkSpec("Exit"))
        )
        val m = Model("DtoNWay", autoCSVReports = false)
        val net = StationNetworkBuilder(spec).build(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()
        assertEquals(3.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun matchViaDtoWithInputTargeting() {
        val spec = QueueingNetworkSpec(
            name = "match",
            sources = listOf(
                SourceSpec("ArrA", const(2.0), maxArrivals = 3, routing = RoutingSpec.Direct("MM#0")),
                SourceSpec("ArrB", const(2.0), maxArrivals = 2, routing = RoutingSpec.Direct("MM#1"))
            ),
            matchStations = listOf(MatchStationSpec("MM", numInputs = 2, routing = RoutingSpec.Direct("Exit"))),
            sinks = listOf(SinkSpec("Exit"))
        )
        val m = Model("DtoMatch", autoCSVReports = false)
        val net = StationNetworkBuilder(spec).build(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()
        // 3 from input 0 and 2 from input 1 -> 2 assemblies reach the sink
        assertEquals(2.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
    }
}
