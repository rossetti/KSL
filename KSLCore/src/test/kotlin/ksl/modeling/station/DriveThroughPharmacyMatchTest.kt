package ksl.modeling.station

import ksl.examples.book.chapter4.DriveThroughPharmacy
import ksl.examples.general.models.station.DriveThroughPharmacyStation
import ksl.modeling.station.config.QueueingNetworkModelBuilder
import ksl.modeling.station.config.QueueingNetworkSpec
import ksl.modeling.station.config.QueueingNetworkToml
import ksl.modeling.station.config.RoutingSpec
import ksl.modeling.station.config.SinkSpec
import ksl.modeling.station.config.SourceSpec
import ksl.modeling.station.config.StationSpec
import ksl.simulation.Model
import ksl.modeling.station.queueingNetwork
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.RVData
import kotlin.math.abs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies that the Drive-Through Pharmacy (book chapter 4) is faithfully
 * reproduced by the station network in its three forms (non-DSL, DSL, DTO/TOML).
 *
 * The legacy is an event-graph implementation (hand-coded `EventAction`s), so
 * its stream consumption order does not match the station path. Agreement is
 * therefore statistical (within tolerance) rather than bit-identical.
 *
 * Config: 30 reps, length 20000, warmup 5000, default KSL seed. M/M/1 with
 * arrival rate 1.0 and service rate 2.0 gives ρ = 0.5, analytical mean number
 * in system = ρ/(1-ρ) = 1.0 and mean system time = 1/(μ-λ) = 1.0.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DriveThroughPharmacyMatchTest {

    private companion object {
        const val REPS = 30
        const val LENGTH = 20000.0
        const val WARMUP = 5000.0
        // analytical M/M/1 with rho = 0.5
        const val ANALYTICAL_NS = 1.0
        // generous band: replication noise on the legacy's NS across 30 reps is non-trivial
        const val NS_BAND = 0.20
    }

    private fun legacyNumInSystem(): Double {
        val m = Model("LegacyPharmacy", autoCSVReports = false)
        DriveThroughPharmacy(m, numServers = 1, name = "Pharmacy")
        m.numberOfReplications = REPS
        m.lengthOfReplication = LENGTH
        m.lengthOfReplicationWarmUp = WARMUP
        m.simulate()
        val ns = m.response("# in System")
        assertNotNull(ns, "legacy '# in System' response not found")
        return ns!!.acrossReplicationStatistic.average
    }

    @Test
    fun handBuiltStationNetworkMatchesLegacy() {
        val m = Model("StationPharmacy", autoCSVReports = false)
        val ph = DriveThroughPharmacyStation(m, numServers = 1, name = "Pharmacy")
        m.numberOfReplications = REPS
        m.lengthOfReplication = LENGTH
        m.lengthOfReplicationWarmUp = WARMUP
        m.simulate()

        val stationNS = ph.network.numInSystem.acrossReplicationStatistic.average
        val legacyNS = legacyNumInSystem()
        // both near the analytical mean
        assertTrue(
            abs(stationNS - ANALYTICAL_NS) < NS_BAND,
            "station NS=$stationNS not within $NS_BAND of analytical $ANALYTICAL_NS"
        )
        assertTrue(
            abs(legacyNS - ANALYTICAL_NS) < NS_BAND,
            "legacy NS=$legacyNS not within $NS_BAND of analytical $ANALYTICAL_NS"
        )
        // and to each other (a tighter band since both should agree on the same system)
        assertTrue(
            abs(stationNS - legacyNS) < 0.5 * NS_BAND,
            "station NS=$stationNS and legacy NS=$legacyNS differ by more than ${0.5 * NS_BAND}"
        )
    }

    @Test
    fun dslMatchesHandBuilt() {
        // The DSL is a thin facade over the same constructors; the topology is
        // structurally identical, with the same RVs in the same construction
        // order. Expected to be bit-identical.
        val m1 = Model("HandBuilt", autoCSVReports = false)
        val ph1 = DriveThroughPharmacyStation(m1, numServers = 1, name = "Pharmacy")
        m1.numberOfReplications = REPS
        m1.lengthOfReplication = LENGTH
        m1.lengthOfReplicationWarmUp = WARMUP
        m1.simulate()

        val m2 = Model("Dsl", autoCSVReports = false)
        val net = m2.queueingNetwork("Pharmacy") {
            val exit = sink("Exit")
            val pharmacist = station("Pharmacist", ExponentialRV(0.5, 2), capacity = 1, nextReceiver = exit)
            val arrivals = source("Arrivals", ExponentialRV(1.0, 1))
            arrivals routeTo pharmacist
        }
        m2.numberOfReplications = REPS
        m2.lengthOfReplication = LENGTH
        m2.lengthOfReplicationWarmUp = WARMUP
        m2.simulate()

        assertEquals(
            ph1.network.numInSystem.acrossReplicationStatistic.average,
            net.numInSystem.acrossReplicationStatistic.average,
            1e-9, "DSL and hand-built should produce identical NS"
        )
        assertEquals(
            ph1.network.systemTime.acrossReplicationStatistic.average,
            net.systemTime.acrossReplicationStatistic.average,
            1e-9, "DSL and hand-built should produce identical system time"
        )
    }

    @Test
    fun dtoFromTomlBuildsAndMatches() {
        fun exp(mean: Double) = RVData(RVType.Exponential, mapOf("mean" to doubleArrayOf(mean)))
        val spec = QueueingNetworkSpec(
            name = "Pharmacy",
            sources = listOf(SourceSpec("Arrivals", exp(1.0), routing = RoutingSpec.Direct("Pharmacist"))),
            stations = listOf(StationSpec("Pharmacist", exp(0.5), capacity = 1, routing = RoutingSpec.Direct("Exit"))),
            sinks = listOf(SinkSpec("Exit"))
        )
        val toml = QueueingNetworkToml.encode(spec)
        // verify TOML round-trips and we can build a runnable model from the text
        val model = QueueingNetworkModelBuilder.fromToml(toml).build()
        model.numberOfReplications = REPS
        model.lengthOfReplication = LENGTH
        model.lengthOfReplicationWarmUp = WARMUP
        model.simulate()

        val ns = model.response("Pharmacy:NumInSystem")
        assertNotNull(ns, "expected 'Pharmacy:NumInSystem' response on the DTO-built model")
        val nsAvg = ns!!.acrossReplicationStatistic.average

        // DTO uses auto-assigned streams (not pinned), so the comparison is
        // analytical/statistical: within the band of the M/M/1 mean
        assertTrue(
            abs(nsAvg - ANALYTICAL_NS) < NS_BAND,
            "DTO NS=$nsAvg not within $NS_BAND of analytical $ANALYTICAL_NS"
        )
    }
}
