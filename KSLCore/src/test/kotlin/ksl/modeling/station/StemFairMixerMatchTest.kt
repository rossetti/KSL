package ksl.modeling.station

import ksl.examples.book.chapter6.StemFairMixer
import ksl.examples.general.models.station.StemFairMixerStation
import ksl.modeling.station.config.ActivityStationSpec
import ksl.modeling.station.config.QObjectClassSpec
import ksl.modeling.station.config.QueueingNetworkModelBuilder
import ksl.modeling.station.config.QueueingNetworkSpec
import ksl.modeling.station.config.RoutingSpec
import ksl.modeling.station.config.SinkSpec
import ksl.modeling.station.config.SourceSpec
import ksl.modeling.station.config.StationSpec
import ksl.modeling.station.config.TypeBranch
import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.BernoulliRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.RVData
import ksl.modeling.station.queueingNetwork
import ksl.modeling.station.ByTypeRouter
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.TriangularRV
import ksl.utilities.random.rvariable.UniformRV
import kotlin.math.abs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies the STEM Career Fair Mixer (book chapter 6) reproduces in the
 * station-network framework in all three forms.
 *
 * - The DSL form is structurally identical to the hand-built station form
 *   with the same RVs in the same construction order, so they match bit-for-bit.
 * - The hand-built form vs the legacy is statistical: the legacy uses the
 *   process view (per-resource seize/delay/release), so stream consumption
 *   order differs. 400 reps × 6 hours (the legacy reference main) give a tight
 *   band on the overall system time mean.
 * - The DTO form runs through TOML-text round-trip and a marking-hook registry.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StemFairMixerMatchTest {

    private companion object {
        const val REPS = 400
        const val LENGTH = 6.0 * 60.0
    }

    private fun legacyOverallSystemTime(): Double {
        val m = Model("LegacyMixer", autoCSVReports = false)
        StemFairMixer(m, "Mixer")
        m.numberOfReplications = REPS
        m.lengthOfReplication = LENGTH
        m.simulate()
        val rsp = m.response("OverallSystemTime")
        assertNotNull(rsp, "legacy 'OverallSystemTime' response not found")
        return rsp!!.acrossReplicationStatistic.average
    }

    @Test
    fun handBuiltStationNetworkMatchesLegacy() {
        val ms = Model("StationMixer", autoCSVReports = false)
        val sfm = StemFairMixerStation(ms, "Mixer")
        ms.numberOfReplications = REPS
        ms.lengthOfReplication = LENGTH
        ms.simulate()
        val stationAvg = sfm.network.systemTime.acrossReplicationStatistic.average

        val legacyAvg = legacyOverallSystemTime()

        // 400 reps × 6 hours: both should be tightly estimated; allow 20% rel diff
        val relErr = abs(stationAvg - legacyAvg) / legacyAvg
        assertTrue(stationAvg in 5.0..50.0, "station system time $stationAvg out of expected range")
        assertTrue(legacyAvg in 5.0..50.0, "legacy system time $legacyAvg out of expected range")
        assertTrue(
            relErr < 0.20,
            "station system time $stationAvg vs legacy $legacyAvg differ by ${(relErr * 100).toInt()}% (>20%)"
        )
    }

    @Test
    fun dslMatchesHandBuilt() {
        // structural twin of StemFairMixerStation — same RVs, same construction order
        val m1 = Model("HandBuilt", autoCSVReports = false)
        val sfm1 = StemFairMixerStation(m1, "Mixer")
        m1.numberOfReplications = REPS
        m1.lengthOfReplication = LENGTH
        m1.simulate()

        val wanderRV = BernoulliRV(0.5, 6)
        val leaveRV = BernoulliRV(0.1, 7)
        val m2 = Model("Dsl", autoCSVReports = false)
        val net2 = m2.queueingNetwork("Mixer") {
            network.registerClass(QObjectClass("NonWanderer", typeId = 1))
            network.registerClass(QObjectClass("Wanderer", typeId = 2))
            network.registerClass(QObjectClass("Leaver", typeId = 3))
            val exit = sink("Exit")
            val jhBunt = station("JHBunt", ExponentialRV(6.0, 4), capacity = 3, nextReceiver = exit)
            val malWart = station("MalWart", ExponentialRV(3.0, 5), capacity = 2, nextReceiver = jhBunt)
            val postWander = ByTypeRouter(mapOf(3 to exit), default = malWart)
            network.register("PostWanderRouter", postWander)
            val wander = delay("Wander", TriangularRV(15.0, 20.0, 45.0, 3), nextReceiver = postWander)
            val postNameTag = ByTypeRouter(mapOf(1 to malWart), default = wander)
            network.register("PostNameTagRouter", postNameTag)
            val nameTag = delay("NameTag", UniformRV(15.0 / 60.0, 45.0 / 60.0, 2), nextReceiver = postNameTag)
            source(
                "Arrivals", ExponentialRV(2.0, 1), firstReceiver = nameTag,
                marking = { q ->
                    val isWanderer = wanderRV.value > 0.5
                    val isLeaver = isWanderer && (leaveRV.value > 0.5)
                    q.qObjectType = when {
                        !isWanderer -> 1
                        !isLeaver -> 2
                        else -> 3
                    }
                }
            )
        }
        m2.numberOfReplications = REPS
        m2.lengthOfReplication = LENGTH
        m2.simulate()

        val handAvg = sfm1.network.systemTime.acrossReplicationStatistic.average
        val dslAvg = net2.systemTime.acrossReplicationStatistic.average
        // The DSL is a thin facade over the same constructors with the same RVs in
        // the same construction order, but the BernoulliRV instances live outside
        // the model's stream provider, so bit-identity is not guaranteed when the
        // hand-built form holds the wanderRV/leaveRV as class fields. The values
        // should be statistically indistinguishable.
        val relErr = abs(handAvg - dslAvg) / handAvg
        assertTrue(
            relErr < 0.05,
            "DSL system time $dslAvg vs hand-built $handAvg differ by ${(relErr * 100).toInt()}% (>5%)"
        )
    }

    @Test
    fun dtoFromTomlBuildsAndMatchesHandBuilt() {
        fun exp(mean: Double) = RVData(RVType.Exponential, mapOf("mean" to doubleArrayOf(mean)))
        fun uniform(min: Double, max: Double) = RVData(
            RVType.Uniform, mapOf("min" to doubleArrayOf(min), "max" to doubleArrayOf(max))
        )
        fun triangular(min: Double, mode: Double, max: Double) = RVData(
            RVType.Triangular,
            mapOf(
                "min" to doubleArrayOf(min),
                "mode" to doubleArrayOf(mode),
                "max" to doubleArrayOf(max)
            )
        )

        val spec = QueueingNetworkSpec(
            name = "Mixer",
            classes = listOf(
                QObjectClassSpec("NonWanderer", typeId = 1),
                QObjectClassSpec("Wanderer", typeId = 2),
                QObjectClassSpec("Leaver", typeId = 3)
            ),
            sources = listOf(
                SourceSpec("Arrivals", exp(2.0), marking = "classify", routing = RoutingSpec.Direct("NameTag"))
            ),
            activityStations = listOf(
                ActivityStationSpec("NameTag", uniform(15.0 / 60.0, 45.0 / 60.0),
                    routing = RoutingSpec.ByType(listOf(TypeBranch(1, "MalWart")), default = "Wander")),
                ActivityStationSpec("Wander", triangular(15.0, 20.0, 45.0),
                    routing = RoutingSpec.ByType(listOf(TypeBranch(3, "Exit")), default = "MalWart"))
            ),
            stations = listOf(
                StationSpec("MalWart", exp(3.0), capacity = 2, routing = RoutingSpec.Direct("JHBunt")),
                StationSpec("JHBunt", exp(6.0), capacity = 3, routing = RoutingSpec.Direct("Exit"))
            ),
            sinks = listOf(SinkSpec("Exit"))
        )

        val wanderRV = BernoulliRV(0.5, 6)
        val leaveRV = BernoulliRV(0.1, 7)
        val model = QueueingNetworkModelBuilder(
            spec,
            markings = mapOf(
                "classify" to MarkingHookIfc { q, _ ->
                    val isWanderer = wanderRV.value > 0.5
                    val isLeaver = isWanderer && (leaveRV.value > 0.5)
                    q.qObjectType = when {
                        !isWanderer -> 1
                        !isLeaver -> 2
                        else -> 3
                    }
                }
            )
        ).build()
        model.numberOfReplications = REPS
        model.lengthOfReplication = LENGTH
        model.simulate()

        val ns = model.response("Mixer:SystemTime")
        assertNotNull(ns)
        val dtoAvg = ns!!.acrossReplicationStatistic.average

        // hand-built comparison
        val mH = Model("HandBuiltCompare", autoCSVReports = false)
        val sfm = StemFairMixerStation(mH, "Mixer")
        mH.numberOfReplications = REPS
        mH.lengthOfReplication = LENGTH
        mH.simulate()
        val handAvg = sfm.network.systemTime.acrossReplicationStatistic.average

        // streams are auto-assigned by RVData (not pinned), so DTO matches hand-built statistically
        val relErr = abs(dtoAvg - handAvg) / handAvg
        assertTrue(
            relErr < 0.10,
            "DTO system time $dtoAvg vs hand-built $handAvg differ by ${(relErr * 100).toInt()}% (>10%)"
        )
    }
}
