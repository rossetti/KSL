package ksl.modeling.station

import ksl.examples.book.chapter4.TandemQueue
import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import kotlin.test.assertTrue

/**
 * Phase-1 tests for the [queueingNetwork] builder DSL and the network graph /
 * validation it builds upon.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationNetworkDslTest {

    @Test
    fun dslEquivalentToLegacyTandemQueue() {
        val md = Model("DslTQ", autoCSVReports = false)
        val net = md.queueingNetwork("tandem") {
            val exit = sink("Exit")
            val s1 = station("Station1", ExponentialRV(4.0, 2))
            val s2 = station("Station2", ExponentialRV(3.0, 3), nextReceiver = exit)
            val arrivals = source("Arrivals", ExponentialRV(6.0, 1))
            arrivals routeTo s1
            s1 routeTo s2
        }
        md.numberOfReplications = 30
        md.lengthOfReplication = 20000.0
        md.lengthOfReplicationWarmUp = 5000.0
        md.simulate()

        val ml = Model("LegacyTQ", autoCSVReports = false)
        val legacy = TandemQueue(ml, "TQ")
        ml.numberOfReplications = 30
        ml.lengthOfReplication = 20000.0
        ml.lengthOfReplicationWarmUp = 5000.0
        ml.simulate()

        assertEquals(
            legacy.numInSystem.acrossReplicationStatistic.average,
            net.numInSystem.acrossReplicationStatistic.average, 1e-9
        )
        assertEquals(
            legacy.totalSystemTime.acrossReplicationStatistic.average,
            net.systemTime.acrossReplicationStatistic.average, 1e-9
        )
        assertEquals(
            legacy.totalProcessed.acrossReplicationStatistic.average,
            net.numCompleted.acrossReplicationStatistic.average, 1e-9
        )
    }

    @Test
    fun graphIntrospection() {
        val md = Model("Graph", autoCSVReports = false)
        val net = md.queueingNetwork("tandem") {
            val exit = sink("Exit")
            val s1 = station("Station1", ExponentialRV(4.0, 2))
            val s2 = station("Station2", ExponentialRV(3.0, 3), nextReceiver = exit)
            val arrivals = source("Arrivals", ExponentialRV(6.0, 1))
            arrivals routeTo s1
            s1 routeTo s2
        }
        assertEquals(setOf("Exit", "Station1", "Station2", "Arrivals"), net.nodeNames)
        assertEquals(
            setOf(
                NetworkArc("Arrivals", "Station1"),
                NetworkArc("Station1", "Station2"),
                NetworkArc("Station2", "Exit")
            ),
            net.arcs().toSet()
        )
    }

    @Test
    fun forwardReferenceByName() {
        val md = Model("Fwd", autoCSVReports = false)
        val net = md.queueingNetwork("fwd") {
            val exit = sink("Exit")
            val arrivals = source("Arrivals", ConstantRV(1.0), maxArrivals = 10)
            arrivals routeTo "Worker"                       // forward reference, resolved after block
            station("Worker", ConstantRV.ZERO) routeTo exit
        }
        md.numberOfReplications = 1
        md.lengthOfReplication = 50.0
        md.simulate()
        assertEquals(10.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun unknownNameThrowsAtBuild() {
        val md = Model("Bad", autoCSVReports = false)
        assertThrows(IllegalStateException::class.java) {
            md.queueingNetwork("bad") {
                sink("Exit")
                val arrivals = source("Arrivals", ConstantRV(1.0))
                arrivals routeTo "DoesNotExist"
            }
        }
    }

    @Test
    fun byChanceReworkRunsAndIsIntrospectable() {
        val md = Model("Rework", autoCSVReports = false)
        val net = md.queueingNetwork("rework") {
            val exit = sink("Exit")
            val inspect = station("Inspect", ExponentialRV(2.0, 2))
            val arrivals = source("Arrivals", ExponentialRV(5.0, 1))
            arrivals routeTo inspect
            inspect.routeByChance(0.8 to exit, 0.2 to inspect)   // 20% rework
        }
        md.numberOfReplications = 5
        md.lengthOfReplication = 20000.0
        md.lengthOfReplicationWarmUp = 2000.0
        md.simulate()
        assertTrue(net.numCompleted.acrossReplicationStatistic.average > 0.0)
        // the probabilistic router is a registered, introspectable node whose
        // destinations include both branch targets
        val routerName = net.nodeNames.first { it.startsWith("router_") }
        val outArcs = net.arcs().filter { it.from == routerName }.map { it.to }.toSet()
        assertEquals(setOf("Exit", "Inspect"), outArcs)
    }

    @Test
    fun danglingNodeFailsValidation() {
        val md = Model("Dangling", autoCSVReports = false)
        md.queueingNetwork("dangling") {
            sink("Exit")
            val arrivals = source("Arrivals", ConstantRV(1.0))
            // Worker has no onward routing and is not on any route -> dangling
            val worker = station("Worker", ConstantRV.ZERO)
            arrivals routeTo worker
        }
        md.numberOfReplications = 1
        md.lengthOfReplication = 50.0
        assertThrows(IllegalArgumentException::class.java) { md.simulate() }
    }
}
