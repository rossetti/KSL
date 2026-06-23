package ksl.modeling.station

import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * Phase-0 Step B tests for the QObject-aware routers and the first-class [Route].
 *
 * Most routing tests are made deterministic (constant inter-arrivals, zero service,
 * a single replication) so exact routed counts can be asserted; the join-shortest-
 * queue test asserts load balance, and the route test asserts bit-identical
 * equivalence to direct next-receiver wiring.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationRoutingTest {

    @Test
    fun byTypeRouterSplitsByType() {
        val m = Model("ByType", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val a = net.singleQStation("A", ConstantRV.ZERO, nextReceiver = exit)
        val b = net.singleQStation("B", ConstantRV.ZERO, nextReceiver = exit)
        val router = ByTypeRouter(mapOf(1 to a, 2 to b), default = a)
        net.register("Dispatch", router)
        var count = 0
        net.source(
            "Arrivals", ConstantRV(1.0), firstReceiver = router, maxArrivals = 100,
            marking = { q -> q.qObjectType = if (count++ % 2 == 0) 1 else 2 }
        )
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()
        assertEquals(50.0, a.numProcessed.acrossReplicationStatistic.average, 1e-9)
        assertEquals(50.0, b.numProcessed.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun conditionalRouterSeesQObject() {
        val m = Model("Conditional", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val a = net.singleQStation("A", ConstantRV.ZERO, nextReceiver = exit)
        val b = net.singleQStation("B", ConstantRV.ZERO, nextReceiver = exit)
        // route the first 50 (priority < 50) to A, the rest to B
        val router = ConditionalRouter(
            listOf(RoutingCase({ q -> q.priority < 50 }, a)),
            default = b
        )
        net.register("Dispatch", router)
        var count = 0
        net.source(
            "Arrivals", ConstantRV(1.0), firstReceiver = router, maxArrivals = 100,
            marking = { q -> q.priority = count++ }
        )
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()
        assertEquals(50.0, a.numProcessed.acrossReplicationStatistic.average, 1e-9)
        assertEquals(50.0, b.numProcessed.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun roundRobinRouterAlternatesAndResets() {
        val m = Model("RoundRobin", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val a = net.singleQStation("A", ConstantRV.ZERO, nextReceiver = exit)
        val b = net.singleQStation("B", ConstantRV.ZERO, nextReceiver = exit)
        val router = RoundRobinRouter(listOf(a, b))
        net.register("Dispatch", router)
        net.source("Arrivals", ConstantRV(1.0), firstReceiver = router, maxArrivals = 100)
        // two replications: the router must reset its cursor each replication
        m.numberOfReplications = 2
        m.lengthOfReplication = 200.0
        m.simulate()
        assertEquals(50.0, a.numProcessed.acrossReplicationStatistic.average, 1e-9)
        assertEquals(50.0, b.numProcessed.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun shortestQueueBalancesLoad() {
        val m = Model("JSQ", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val s1 = net.singleQStation("S1", ExponentialRV(0.8, 2), nextReceiver = exit)
        val s2 = net.singleQStation("S2", ExponentialRV(0.8, 3), nextReceiver = exit)
        val jsq = ShortestQueueRouter(listOf(s1, s2))
        net.register("Dispatch", jsq)
        net.source("Arrivals", ExponentialRV(0.5, 1), firstReceiver = jsq)
        m.numberOfReplications = 1
        m.lengthOfReplication = 20000.0
        m.lengthOfReplicationWarmUp = 2000.0
        m.simulate()
        val a = s1.numProcessed.acrossReplicationStatistic.average
        val b = s2.numProcessed.acrossReplicationStatistic.average
        assertTrue(a > 0.0 && b > 0.0, "both servers should process work")
        assertTrue(abs(a - b) < 0.1 * (a + b), "JSQ should balance load: a=$a b=$b")
    }

    @Test
    fun routeEquivalentToDirectWiring() {
        // direct next-receiver wiring
        val m1 = Model("Direct", autoCSVReports = false)
        val net1 = StationNetwork(m1, "Net")
        val exit1 = net1.sink("Exit")
        val d1 = net1.singleQStation("S1", ExponentialRV(0.7, 2))
        val d2 = net1.singleQStation("S2", ExponentialRV(0.5, 3), nextReceiver = exit1)
        d1.nextReceiver(d2)
        val src1 = net1.source("Arrivals", ExponentialRV(1.0, 1))
        src1.firstReceiver(d1)
        m1.numberOfReplications = 5
        m1.lengthOfReplication = 20000.0
        m1.lengthOfReplicationWarmUp = 5000.0
        m1.simulate()

        // same model, but the QObject carries a Route instead of wired links
        val m2 = Model("Routed", autoCSVReports = false)
        val net2 = StationNetwork(m2, "Net")
        val exit2 = net2.sink("Exit")
        val r1 = net2.singleQStation("S1", ExponentialRV(0.7, 2))
        val r2 = net2.singleQStation("S2", ExponentialRV(0.5, 3))
        val route = Route("plan", listOf(r1, r2, exit2))
        net2.registerRoute(route) // route-driven stations are exempt from the dangling check
        val src2 = net2.source("Arrivals", ExponentialRV(1.0, 1))
        src2.firstReceiver(route.entryReceiver())
        m2.numberOfReplications = 5
        m2.lengthOfReplication = 20000.0
        m2.lengthOfReplicationWarmUp = 5000.0
        m2.simulate()

        assertEquals(
            net1.systemTime.acrossReplicationStatistic.average,
            net2.systemTime.acrossReplicationStatistic.average,
            1e-9, "routed system time should equal directly wired"
        )
        assertEquals(
            net1.numCompleted.acrossReplicationStatistic.average,
            net2.numCompleted.acrossReplicationStatistic.average,
            1e-9, "routed completions should equal directly wired"
        )
    }
}
