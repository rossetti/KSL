package ksl.modeling.station.config

import ksl.modeling.station.SingleQStation
import ksl.modeling.station.StationNetwork
import ksl.modeling.station.queueingNetwork
import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.RVData
import kotlin.test.assertTrue

/**
 * Phase-4 DTO/TOML coverage for Phase-2 resource enrichment on single-queue
 * stations: capacity schedules, failures, and sequence-dependent setups. Values
 * mirror the verified non-DTO deterministic tests.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueueingNetworkResourceConfigTest {

    private fun const(x: Double) = RVData(RVType.Constant, mapOf("value" to doubleArrayOf(x)))

    private fun server(net: StationNetwork) = net.node("Server") as SingleQStation

    @Test
    fun tomlRoundTripStableForResourceEnrichment() {
        val spec = QueueingNetworkSpec(
            name = "enriched",
            sources = listOf(SourceSpec("Arrivals", const(1.0), routing = RoutingSpec.Direct("Server"))),
            stations = listOf(
                StationSpec(
                    "Server", const(2.0), routing = RoutingSpec.Direct("Exit"),
                    capacitySchedule = CapacityScheduleSpec(
                        items = listOf(CapacityItemSpec(1, 480.0), CapacityItemSpec(0, 480.0)),
                        repeatable = true
                    ),
                    failure = FailureSpec.TimeBased(const(100.0), const(20.0), FailureEffectSpec.FINISH_THEN_FAIL),
                    setup = SetupSpec.SequenceDependent(
                        setups = listOf(SetupEntry(1, 2, 5.0), SetupEntry(2, 1, 3.0)),
                        initialSetups = listOf(InitialSetupEntry(1, 2.0))
                    )
                )
            ),
            sinks = listOf(SinkSpec("Exit"))
        )
        val t1 = QueueingNetworkToml.encode(spec)
        val t2 = QueueingNetworkToml.encode(QueueingNetworkToml.decode(t1))
        assertEquals(t1, t2, "resource-enrichment TOML re-encode should be stable")
        assertTrue(t1.contains("capacitySchedule"))
        assertTrue(t1.contains("timeBased"))
        assertTrue(t1.contains("sequenceDependent"))
    }

    @Test
    fun capacityScheduleViaDto() {
        val spec = QueueingNetworkSpec(
            name = "sched",
            sources = listOf(SourceSpec("Arrivals", const(1.0), maxArrivals = 3, routing = RoutingSpec.Direct("Server"))),
            stations = listOf(
                StationSpec(
                    "Server", const(1.0), routing = RoutingSpec.Direct("Exit"),
                    capacitySchedule = CapacityScheduleSpec(listOf(CapacityItemSpec(0, 5.0), CapacityItemSpec(1, 995.0)))
                )
            ),
            sinks = listOf(SinkSpec("Exit"))
        )
        val m = Model("DtoSched", autoCSVReports = false)
        val net = StationNetworkBuilder(spec).build(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()
        // off-shift [0,5) holds the 3 arrivals; served from t=5 -> system time 5.0
        assertEquals(3.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(5.0, net.systemTime.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun countBasedFailureViaDto() {
        val spec = QueueingNetworkSpec(
            name = "fail",
            sources = listOf(SourceSpec("Arrivals", const(10.0), maxArrivals = 4, routing = RoutingSpec.Direct("Server"))),
            stations = listOf(
                StationSpec(
                    "Server", const(2.0), routing = RoutingSpec.Direct("Exit"),
                    failure = FailureSpec.CountBased(countToFailure = const(2.0), timeToRepair = const(3.0))
                )
            ),
            sinks = listOf(SinkSpec("Exit"))
        )
        val m = Model("DtoFail", autoCSVReports = false)
        val net = StationNetworkBuilder(spec).build(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()
        assertEquals(4.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(2.0, server(net).resource.numTimesFailed.acrossReplicationStatistic.average, 1e-9)
        assertEquals(6.0 / 50.0, server(net).resource.failedStateProportion.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun setupViaDto() {
        val spec = QueueingNetworkSpec(
            name = "setup",
            classes = listOf(QObjectClassSpec("A", typeId = 1)),
            sources = listOf(SourceSpec("Arrivals", const(10.0), maxArrivals = 3, entityClass = "A", routing = RoutingSpec.Direct("Server"))),
            stations = listOf(
                StationSpec("Server", const(2.0), routing = RoutingSpec.Direct("Exit"), setup = SetupSpec.Changeover(1.0))
            ),
            sinks = listOf(SinkSpec("Exit"))
        )
        val m = Model("DtoSetup", autoCSVReports = false)
        val net = StationNetworkBuilder(spec).build(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        m.simulate()
        // all type 1: only the first job changes over (from null) -> setups 1,0,0
        // system times: 3,2,2 -> average 7/3; mean setup 1/3
        assertEquals(3.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(7.0 / 3.0, net.systemTime.acrossReplicationStatistic.average, 1e-9)
        assertEquals(1.0 / 3.0, server(net).setupTimeResponse.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun resourceEnrichmentViaDsl() {
        // the DSL exposes enrichment via the returned station's public methods
        val m = Model("DslEnrich", autoCSVReports = false)
        val net = m.queueingNetwork("dsl") {
            val exit = sink("Exit")
            val s = station("Server", ExponentialRV(0.7, 2), nextReceiver = exit)
            s.useTimeBasedFailures(ExponentialRV(100.0, 3), ExponentialRV(20.0, 4))
            source("Arrivals", ExponentialRV(1.0, 1)) routeTo s
        }
        m.numberOfReplications = 5
        m.lengthOfReplication = 20000.0
        m.lengthOfReplicationWarmUp = 2000.0
        m.simulate()
        assertTrue(server(net).resource.numTimesFailed.acrossReplicationStatistic.average > 0.0)
    }
}
