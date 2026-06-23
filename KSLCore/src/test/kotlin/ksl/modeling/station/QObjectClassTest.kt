package ksl.modeling.station

import ksl.modeling.variable.RandomVariable
import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import kotlin.test.assertTrue

/**
 * Phase-0 Step C tests for [QObjectClass] and the network's per-class statistics.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QObjectClassTest {

    @Test
    fun deterministicPerClassSystemTime() {
        val m = Model("PerClass", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val server = net.singleQStation("Server", nextReceiver = exit)
        server.useQObjectForActivityTime = true
        val classA = QObjectClass("A", typeId = 1, valueObject = ConstantRV(3.0))
        // arrivals spaced (10) well beyond service (3): no queueing, so system time == service
        net.source("Arrivals", ConstantRV(10.0), firstReceiver = server, maxArrivals = 20, qObjectClass = classA)
        m.numberOfReplications = 1
        m.lengthOfReplication = 300.0
        m.simulate()

        assertEquals(3.0, net.classSystemTime("A")!!.acrossReplicationStatistic.average, 1e-9)
        assertEquals(20.0, net.classNumCompleted("A")!!.acrossReplicationStatistic.average, 1e-9)
        assertEquals(3.0, net.systemTime.acrossReplicationStatistic.average, 1e-9)
        assertEquals(20.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun twoClassesTrackedSeparately() {
        val m = Model("TwoClass", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val server = net.singleQStation("Server", nextReceiver = exit)
        server.useQObjectForActivityTime = true
        val a = QObjectClass("A", typeId = 1, valueObject = RandomVariable(net, ExponentialRV(1.0, 2)))
        val b = QObjectClass("B", typeId = 2, valueObject = RandomVariable(net, ExponentialRV(6.0, 3)))
        net.source("ArrA", ExponentialRV(10.0, 1), firstReceiver = server, qObjectClass = a)
        net.source("ArrB", ExponentialRV(10.0, 4), firstReceiver = server, qObjectClass = b)
        m.numberOfReplications = 10
        m.lengthOfReplication = 20000.0
        m.lengthOfReplicationWarmUp = 2000.0
        m.simulate()

        assertEquals(setOf("A", "B"), net.classNames)
        val sa = net.classSystemTime("A")!!.acrossReplicationStatistic.average
        val sb = net.classSystemTime("B")!!.acrossReplicationStatistic.average
        assertTrue(sb > sa, "class B (longer service) should have larger system time: a=$sa b=$sb")

        val ca = net.classNumCompleted("A")!!.acrossReplicationStatistic.average
        val cb = net.classNumCompleted("B")!!.acrossReplicationStatistic.average
        assertTrue(ca > 0.0 && cb > 0.0)
        // per-class completions partition the total
        assertEquals(net.numCompleted.acrossReplicationStatistic.average, ca + cb, 1e-6)
    }

    @Test
    fun unknownClassNameReturnsNull() {
        val m = Model("Unknown", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        net.registerClass(QObjectClass("A", typeId = 1))
        assertEquals(null, net.classSystemTime("Nope"))
        assertEquals(null, net.classNumCompleted("Nope"))
    }

    @Test
    fun conflictingClassTypeIdThrows() {
        val m = Model("Conflict", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        net.registerClass(QObjectClass("A", typeId = 1))
        // same type id, different name -> conflict
        assertThrows(IllegalArgumentException::class.java) {
            net.registerClass(QObjectClass("B", typeId = 1))
        }
        // re-registering the same class is a no-op (no throw)
        net.registerClass(QObjectClass("A", typeId = 1))
    }
}
