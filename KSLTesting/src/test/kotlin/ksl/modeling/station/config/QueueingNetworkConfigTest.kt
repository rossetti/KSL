package ksl.modeling.station.config

import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.RVData
import kotlin.test.assertTrue

/**
 * Phase-4 tests for the serializable [QueueingNetworkSpec], its TOML/JSON codecs,
 * and the [StationNetworkBuilder] that turns a spec into a live network.
 *
 * Round-trips are checked by re-encoding (RVData holds DoubleArrays whose equals is
 * identity-based, so spec equality is unreliable). Built networks are verified by
 * simulating and checking analytical/structural properties — not bit-identity,
 * since the DTO does not pin random-number streams.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueueingNetworkConfigTest {

    private fun exp(mean: Double) = RVData(RVType.Exponential, mapOf("mean" to doubleArrayOf(mean)))

    private fun tandemSpec() = QueueingNetworkSpec(
        name = "tandem",
        sources = listOf(
            SourceSpec("Arrivals", exp(6.0), routing = RoutingSpec.Direct("Station1"))
        ),
        stations = listOf(
            StationSpec("Station1", exp(4.0), routing = RoutingSpec.Direct("Station2")),
            StationSpec("Station2", exp(3.0), routing = RoutingSpec.Direct("Exit"))
        ),
        sinks = listOf(SinkSpec("Exit"))
    )

    @Test
    fun tomlRoundTripStableAndReadable() {
        val spec = tandemSpec()
        val toml1 = QueueingNetworkToml.encode(spec)
        val spec2 = QueueingNetworkToml.decode(toml1)
        val toml2 = QueueingNetworkToml.encode(spec2)
        assertEquals(toml1, toml2, "TOML re-encode should be stable")
        // human-readable tokens
        assertTrue(toml1.contains("Exponential"), "expected distribution type in TOML:\n$toml1")
        assertTrue(toml1.contains("direct"), "expected routing discriminator in TOML:\n$toml1")
    }

    @Test
    fun jsonRoundTripStable() {
        val spec = tandemSpec()
        val json1 = QueueingNetworkJson.encode(spec)
        val spec2 = QueueingNetworkJson.decode(json1)
        val json2 = QueueingNetworkJson.encode(spec2)
        assertEquals(json1, json2)
    }

    @Test
    fun buildAndSimulateTandem() {
        val m = Model("DtoTandem", autoCSVReports = false)
        val net = StationNetworkBuilder(tandemSpec()).build(m)
        m.numberOfReplications = 30
        m.lengthOfReplication = 20000.0
        m.lengthOfReplicationWarmUp = 5000.0
        m.simulate()

        assertEquals(setOf("Arrivals", "Station1", "Station2", "Exit"), net.nodeNames)
        assertTrue(net.numCompleted.acrossReplicationStatistic.average > 0.0)
        // tandem M/M/1: E[T] ~ 12 + 6 = 18; a generous band guards the build
        assertTrue(net.systemTime.acrossReplicationStatistic.average in 12.0..26.0)
    }

    @Test
    fun byChancePipelineFromTomlText() {
        // build a spec with a rework loop, round-trip it through TOML, build, run
        val spec = QueueingNetworkSpec(
            name = "rework",
            sources = listOf(SourceSpec("Arrivals", exp(5.0), routing = RoutingSpec.Direct("Inspect"))),
            stations = listOf(
                StationSpec(
                    "Inspect", exp(2.0),
                    routing = RoutingSpec.ByChance(
                        listOf(ChanceBranch("Exit", 0.8), ChanceBranch("Inspect", 0.2))
                    )
                )
            ),
            sinks = listOf(SinkSpec("Exit"))
        )
        val toml = QueueingNetworkToml.encode(spec)
        assertTrue(toml.contains("byChance"))
        val decoded = QueueingNetworkToml.decode(toml)

        val m = Model("DtoRework", autoCSVReports = false)
        val net = StationNetworkBuilder(decoded).build(m)
        m.numberOfReplications = 5
        m.lengthOfReplication = 20000.0
        m.lengthOfReplicationWarmUp = 2000.0
        m.simulate()
        assertTrue(net.numCompleted.acrossReplicationStatistic.average > 0.0)
    }

    @Test
    fun buildsAllRoutingVariantsAndClasses() {
        val spec = QueueingNetworkSpec(
            name = "all",
            classes = listOf(
                QObjectClassSpec("ClassA", typeId = 1),
                QObjectClassSpec("ClassB", typeId = 2, serviceTime = exp(2.0))
            ),
            sources = listOf(
                SourceSpec("SrcA", exp(5.0), entityClass = "ClassA", routing = RoutingSpec.Direct("Hub")),
                SourceSpec("SrcB", exp(5.0), entityClass = "ClassB", routing = RoutingSpec.ShortestQueue(listOf("W1", "W2")))
            ),
            stations = listOf(
                StationSpec("Hub", routing = RoutingSpec.ByType(listOf(TypeBranch(1, "W1"), TypeBranch(2, "W2")), default = "W1")),
                StationSpec("W1", exp(2.0), routing = RoutingSpec.Direct("Exit")),
                StationSpec("W2", exp(2.0), routing = RoutingSpec.ByChance(listOf(ChanceBranch("Exit", 0.9), ChanceBranch("Hub", 0.1))))
            ),
            sinks = listOf(SinkSpec("Exit"))
        )
        val m = Model("DtoAll", autoCSVReports = false)
        val net = StationNetworkBuilder(spec).build(m)
        m.numberOfReplications = 3
        m.lengthOfReplication = 5000.0
        m.lengthOfReplicationWarmUp = 500.0
        m.simulate()

        assertTrue(net.nodeNames.containsAll(setOf("SrcA", "SrcB", "Hub", "W1", "W2", "Exit")))
        assertTrue(net.arcs().isNotEmpty())
        assertTrue(net.numCompleted.acrossReplicationStatistic.average > 0.0)
    }

    @Test
    fun modelBuilderFromTomlBuildsRunnableModel() {
        val toml = QueueingNetworkToml.encode(tandemSpec())
        val model = QueueingNetworkModelBuilder.fromToml(toml).build()
        model.numberOfReplications = 10
        model.lengthOfReplication = 20000.0
        model.lengthOfReplicationWarmUp = 5000.0
        model.simulate()
        // the network's system-time response is named "<network>:SystemTime"
        val sysTime = model.response("tandem:SystemTime")
        assertTrue(sysTime != null, "expected the network's SystemTime response in the built model")
        assertTrue(sysTime!!.acrossReplicationStatistic.average in 12.0..26.0)
    }
}

