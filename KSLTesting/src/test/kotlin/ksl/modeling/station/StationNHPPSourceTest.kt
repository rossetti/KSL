package ksl.modeling.station

import ksl.modeling.station.config.NHPPSourceSpec
import ksl.modeling.station.config.QueueingNetworkModelBuilder
import ksl.modeling.station.config.QueueingNetworkSpec
import ksl.modeling.station.config.QueueingNetworkToml
import ksl.modeling.station.config.RoutingSpec
import ksl.modeling.station.config.SinkSpec
import ksl.modeling.station.config.StationSpec
import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.RVData
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * Tests the non-homogeneous Poisson arrival source ([NHPPSource]) in the
 * station framework. Verifies both the primitive (network helper + DSL) and
 * the DTO/TOML coverage. Used as the building block for the chapter-7
 * enhanced STEM Fair model with time-varying arrival rates.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationNHPPSourceTest {

    @Test
    fun nhppSourceProducesRoughlyExpectedArrivals() {
        // Two-segment rate function:
        //   first 5 time units: rate 2/unit  (expected 10 arrivals)
        //   next 5 time units:  rate 4/unit  (expected 20 arrivals)
        // Total expected: 30 arrivals per replication
        val m = Model("NHPPSimple", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val server = net.singleQStation("Server", ksl.utilities.random.rvariable.ConstantRV.ZERO, nextReceiver = exit)
        val src = net.nhppSource(
            "Arrivals",
            durations = doubleArrayOf(5.0, 5.0),
            rates = doubleArrayOf(2.0, 4.0),
            firstReceiver = server
        )
        m.numberOfReplications = 200
        m.lengthOfReplication = 10.0
        m.simulate()

        // expected total arrivals = 5*2 + 5*4 = 30; with 200 reps the band is tight
        val avgCreated = src.numCreated.acrossReplicationStatistic.average
        assertTrue(
            abs(avgCreated - 30.0) < 1.5,
            "average created $avgCreated not within 1.5 of expected 30.0"
        )
    }

    @Test
    fun dtoRoundTripAndBuildMatchesPrimitive() {
        // Build via DTO with the same rate function and assert it matches the
        // network helper's output within Monte Carlo noise
        val spec = QueueingNetworkSpec(
            name = "NHPPDto",
            sinks = listOf(SinkSpec("Exit")),
            stations = listOf(
                StationSpec(
                    "Server",
                    activityTime = RVData(RVType.Constant, mapOf("value" to doubleArrayOf(0.0))),
                    routing = RoutingSpec.Direct("Exit")
                )
            ),
            nhppSources = listOf(
                NHPPSourceSpec(
                    "Arrivals",
                    durations = listOf(5.0, 5.0),
                    rates = listOf(2.0, 4.0),
                    routing = RoutingSpec.Direct("Server")
                )
            )
        )
        // round-trip stability
        val toml = QueueingNetworkToml.encode(spec)
        val decoded = QueueingNetworkToml.decode(toml)
        assertEquals(toml, QueueingNetworkToml.encode(decoded))
        assertTrue(toml.contains("nhppSources"))
        assertTrue(toml.contains("durations"))

        val model = QueueingNetworkModelBuilder(spec).build()
        model.numberOfReplications = 200
        model.lengthOfReplication = 10.0
        model.simulate()

        val numCreated = model.counter("NHPPDto:Arrivals:NumCreated")
        assertTrue(numCreated != null, "expected NumCreated counter for NHPP source")
        val avg = numCreated!!.acrossReplicationStatistic.average
        assertTrue(
            abs(avg - 30.0) < 1.5,
            "DTO-built NHPP source avg arrivals $avg not within 1.5 of expected 30.0"
        )
    }
}
