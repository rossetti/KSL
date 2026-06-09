package ksl.modeling.station.config

import ksl.modeling.station.ChildCountIfc
import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.RVData
import kotlin.test.assertTrue

/**
 * Phase-4 DTO/TOML coverage for fork-join: structure (the join's identity and the
 * fork's pairing with it, plus parent and child routings) serializes; behavior
 * (child count, child configuration) is supplied via named-hook registries on
 * the builder. An unknown hook fails the build loudly.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueueingNetworkForkJoinConfigTest {

    private fun const(x: Double) = RVData(RVType.Constant, mapOf("value" to doubleArrayOf(x)))

    /**
     * The reference scenario (same shape as StationForkJoinTest first test): two
     * orders of sizes 3 and 5 fork into children that pass through ChildMaker
     * (zero-delay-ish via 2.0 service, capacity 10), while parents pass through
     * Paperwork (5.0 service) — all roads lead to the join, then the sink.
     */
    private fun spec() = QueueingNetworkSpec(
        name = "tieDyeLike",
        sources = listOf(
            // 2 orders at t=1 and t=51, typed via classes (typeId carries the size)
            SourceSpec("ArrivalsA", const(50.0), timeUntilFirst = const(1.0), maxArrivals = 1, entityClass = "Size3", routing = RoutingSpec.Direct("Fork")),
            SourceSpec("ArrivalsB", const(50.0), timeUntilFirst = const(51.0), maxArrivals = 1, entityClass = "Size5", routing = RoutingSpec.Direct("Fork"))
        ),
        classes = listOf(
            QObjectClassSpec("Size3", typeId = 3),
            QObjectClassSpec("Size5", typeId = 5)
        ),
        stations = listOf(
            StationSpec("ChildMaker", const(2.0), capacity = 10, routing = RoutingSpec.Direct("Join#1")),
            StationSpec("Paperwork", const(5.0), routing = RoutingSpec.Direct("Join"))   // bare 'Join' -> parent input
        ),
        sinks = listOf(SinkSpec("Exit")),
        joinStations = listOf(JoinStationSpec("Join", routing = RoutingSpec.Direct("Exit"))),
        forkStations = listOf(
            ForkStationSpec(
                "Fork", join = "Join",
                childCount = "fromType",
                childRouting = RoutingSpec.Direct("ChildMaker"),
                routing = RoutingSpec.Direct("Paperwork")
            )
        )
    )

    @Test
    fun tomlRoundTripStable() {
        val t1 = QueueingNetworkToml.encode(spec())
        val t2 = QueueingNetworkToml.encode(QueueingNetworkToml.decode(t1))
        assertEquals(t1, t2, "fork-join TOML re-encode should be stable")
        assertTrue(t1.contains("forkStations"))
        assertTrue(t1.contains("joinStations"))
        assertTrue(t1.contains("fromType"))
        // the parent input is the bare join name (default receive); child is name#1
        assertTrue(t1.contains("Join#1"))
    }

    @Test
    fun namedHooksResolveAndDeterministicTimingMatchesPrimitive() {
        val m = Model("DtoForkJoin", autoCSVReports = false)
        val net = StationNetworkBuilder(
            spec(),
            childCounts = mapOf("fromType" to ChildCountIfc { p -> p.qObjectType })
        ).build(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()

        // mirrors StationForkJoinTest.parentForksThenJoinsExactlyItsChildren:
        //   2 orders -> 3+5=8 children -> 2 joins -> system time 5.0
        assertEquals(2.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(5.0, net.systemTime.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun unknownChildCountHookFailsLoudly() {
        val m = Model("DtoMissingHook", autoCSVReports = false)
        val thrown = assertThrows(IllegalStateException::class.java) {
            // no childCount hooks supplied
            StationNetworkBuilder(spec()).build(m)
        }
        assertTrue(thrown.message!!.contains("fromType"), "error should name the missing hook: ${thrown.message}")
    }
}
