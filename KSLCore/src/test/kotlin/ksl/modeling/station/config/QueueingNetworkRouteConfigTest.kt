package ksl.modeling.station.config

import ksl.modeling.station.MarkingHookIfc
import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.RVData
import kotlin.test.assertTrue

/**
 * Phase-4 DTO/TOML coverage for first-class Routes (gap 9). A spec declares
 * named routes by listing the node names of each step; the builder constructs
 * and registers them after all nodes exist. A marking hook can look up routes
 * by name via `network.route(name)` (the second parameter to MarkingHookIfc)
 * and attach the route's sender to the QObject for per-instance routing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueueingNetworkRouteConfigTest {

    private fun const(x: Double) = RVData(RVType.Constant, mapOf("value" to doubleArrayOf(x)))

    /**
     * Two-route deterministic scenario: source marks every other arrival with
     * qObjectType 1 or 2 and attaches PlanA or PlanB. PlanA visits StationA twice;
     * PlanB visits StationB twice. With 100 arrivals at 1-unit spacing, each plan
     * gets 50 entities, each station processes 100 (visited twice by 50 entities).
     */
    private fun twoRouteSpec() = QueueingNetworkSpec(
        name = "RouteDemo",
        sources = listOf(
            SourceSpec(
                "Arrivals", const(1.0), maxArrivals = 100,
                marking = "pickPlan"
                // routing intentionally absent: the marking attaches a route as sender,
                // which the source consults in its receive()
            )
        ),
        stations = listOf(
            StationSpec("StationA", activityTime = null, routing = null),
            StationSpec("StationB", activityTime = null, routing = null)
        ),
        sinks = listOf(SinkSpec("Exit")),
        routes = listOf(
            RouteSpec("PlanA", steps = listOf("StationA", "StationA", "Exit")),
            RouteSpec("PlanB", steps = listOf("StationB", "StationB", "Exit"))
        )
    )

    @Test
    fun tomlRoundTripStable() {
        val toml = QueueingNetworkToml.encode(twoRouteSpec())
        val toml2 = QueueingNetworkToml.encode(QueueingNetworkToml.decode(toml))
        assertEquals(toml, toml2, "RouteSpec TOML re-encode should be stable")
        assertTrue(toml.contains("routes"))
        assertTrue(toml.contains("PlanA"))
        assertTrue(toml.contains("PlanB"))
    }

    @Test
    fun routesAreReachableFromMarkingHook() {
        var i = 0
        val m = Model("DtoRoute", autoCSVReports = false)
        val net = StationNetworkBuilder(
            twoRouteSpec(),
            markings = mapOf(
                "pickPlan" to MarkingHookIfc { q, network ->
                    val planName = if (i++ % 2 == 0) "PlanA" else "PlanB"
                    val route = network.route(planName)
                        ?: error("route '$planName' not found in network")
                    q.qObjectType = if (planName == "PlanA") 1 else 2
                    q.sender(route.newSender())
                }
            )
        ).build(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()

        // 100 arrivals, 50 of each plan, each plan visits its station twice
        val a = m.counter("RouteDemo:StationA:NumProcessed")
        val b = m.counter("RouteDemo:StationB:NumProcessed")
        assertTrue(a != null && b != null, "expected per-station NumProcessed counters")
        assertEquals(100.0, a!!.acrossReplicationStatistic.average, 1e-9)
        assertEquals(100.0, b!!.acrossReplicationStatistic.average, 1e-9)
        // 100 entities reached the exit
        assertEquals(100.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun unknownStepFailsLoudly() {
        val badSpec = QueueingNetworkSpec(
            name = "Bad",
            sources = listOf(SourceSpec("Arrivals", const(1.0), routing = RoutingSpec.Direct("S"))),
            stations = listOf(StationSpec("S", null, routing = RoutingSpec.Direct("Exit"))),
            sinks = listOf(SinkSpec("Exit")),
            routes = listOf(RouteSpec("Bad", steps = listOf("S", "DoesNotExist", "Exit")))
        )
        val m = Model("BadRoute", autoCSVReports = false)
        val thrown = assertThrows(IllegalStateException::class.java) {
            StationNetworkBuilder(badSpec).build(m)
        }
        assertTrue(thrown.message!!.contains("DoesNotExist"), "error should name missing step: ${thrown.message}")
    }
}
