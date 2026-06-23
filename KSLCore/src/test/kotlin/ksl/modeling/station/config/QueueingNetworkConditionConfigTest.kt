package ksl.modeling.station.config

import ksl.modeling.station.QObjectPredicate
import ksl.modeling.station.SingleQStation
import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.RVData
import kotlin.test.assertTrue

/**
 * Phase-4 DTO/TOML coverage for byCondition routing via the named-hook registry.
 * The DTO carries hook names only; the builder takes a Map<String, QObjectPredicate>
 * and fails loudly on an unknown name (the structure/behavior boundary, §6.5/§10.3).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueueingNetworkConditionConfigTest {

    private fun const(x: Double) = RVData(RVType.Constant, mapOf("value" to doubleArrayOf(x)))

    /**
     * Two type-marked classes route to A (type 1) or B (type 2) by a named hook
     * "byType". Using a spec-declared source with a class is the structure-only,
     * round-trippable path; the predicate is the hook resolved at build time.
     */
    private fun routeByTypeHookSpec() = QueueingNetworkSpec(
        name = "byCondition",
        classes = listOf(QObjectClassSpec("TypeA", typeId = 1), QObjectClassSpec("TypeB", typeId = 2)),
        sources = listOf(
            SourceSpec("ArrivalsA", const(2.0), maxArrivals = 50, entityClass = "TypeA", routing = RoutingSpec.Direct("Dispatch")),
            SourceSpec("ArrivalsB", const(2.0), maxArrivals = 50, entityClass = "TypeB", routing = RoutingSpec.Direct("Dispatch"))
        ),
        stations = listOf(
            StationSpec(
                "Dispatch", null,
                routing = RoutingSpec.ByCondition(
                    cases = listOf(ConditionCase(predicate = "isTypeA", to = "A")),
                    default = "B"
                )
            ),
            StationSpec("A", const(0.0), routing = RoutingSpec.Direct("Exit")),
            StationSpec("B", const(0.0), routing = RoutingSpec.Direct("Exit"))
        ),
        sinks = listOf(SinkSpec("Exit"))
    )

    @Test
    fun tomlRoundTripStable() {
        val t1 = QueueingNetworkToml.encode(routeByTypeHookSpec())
        val t2 = QueueingNetworkToml.encode(QueueingNetworkToml.decode(t1))
        assertEquals(t1, t2, "byCondition TOML re-encode should be stable")
        assertTrue(t1.contains("byCondition"))
        assertTrue(t1.contains("isTypeA"))
    }

    @Test
    fun namedHookResolvesAndRoutesDeterministically() {
        val m = Model("DtoByCondition", autoCSVReports = false)
        val net = StationNetworkBuilder(
            routeByTypeHookSpec(),
            predicates = mapOf("isTypeA" to QObjectPredicate { q -> q.qObjectType == 1 })
        ).build(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()

        val a = net.node("A") as SingleQStation
        val b = net.node("B") as SingleQStation
        assertEquals(50.0, a.numProcessed.acrossReplicationStatistic.average, 1e-9)
        assertEquals(50.0, b.numProcessed.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun unknownHookFailsLoudly() {
        val m = Model("DtoMissingHook", autoCSVReports = false)
        val thrown = assertThrows(IllegalStateException::class.java) {
            StationNetworkBuilder(routeByTypeHookSpec(), predicates = emptyMap()).build(m)
        }
        assertTrue(thrown.message!!.contains("isTypeA"), "error should name the missing hook: ${thrown.message}")
    }

    @Test
    fun modelBuilderForwardsPredicates() {
        // round-trip through TOML text and build via the ModelBuilder; the build itself
        // is the assertion (missing hooks fail loudly, so a successful build proves the
        // QueueingNetworkModelBuilder.fromToml overload forwards the predicate registry)
        val toml = QueueingNetworkToml.encode(routeByTypeHookSpec())
        QueueingNetworkModelBuilder.fromToml(
            toml,
            predicates = mapOf("isTypeA" to QObjectPredicate { q -> q.qObjectType == 1 })
        ).build()
    }
}
