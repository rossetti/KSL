package ksl.modeling.station

import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.ConstantRV

/**
 * Phase-3 (slice D) tests for [NWayStation]: several input queues sharing one
 * server, with a cross-queue selection rule.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationNWayTest {

    @Test
    fun strictPriorityServesQueueZeroFirst() {
        val m = Model("NWayPriority", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val nway = net.nWayStation("NWay", numQueues = 2, activityTime = ConstantRV(2.0), nextReceiver = exit)

        val classA = QObjectClass("ClassA", typeId = 1)
        val classB = QObjectClass("ClassB", typeId = 2)
        // queue 0 fed by class A, queue 1 fed by class B
        net.source("SrcA", ConstantRV(1.0), firstReceiver = nway.input(0), maxArrivals = 2, qObjectClass = classA)
        net.source(
            "SrcB", ConstantRV(1.0), firstReceiver = nway.input(1),
            timeUntilFirstRV = ConstantRV(1.5), maxArrivals = 1, qObjectClass = classB
        )
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()

        // a0 t=1 served [1,3]; b0 t=1.5 queued; a1 t=2 queued. At t=3 strict priority
        // picks queue 0 (a1) over the earlier-arrived b0 -> a1 [3,5], then b0 [5,7].
        // class A system times: a0=2, a1=3 -> 2.5; class B (b0)=5.5
        // (a FIFO/round-robin rule would instead give class B ~3.5)
        assertEquals(3.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(3.0, nway.numProcessed.acrossReplicationStatistic.average, 1e-9)
        assertEquals(2.5, net.classSystemTime("ClassA")!!.acrossReplicationStatistic.average, 1e-9)
        assertEquals(5.5, net.classSystemTime("ClassB")!!.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun roundRobinAlternatesAcrossQueues() {
        val m = Model("NWayRR", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val nway = net.nWayStation(
            "NWay", numQueues = 2, activityTime = ConstantRV(2.0),
            selectionRule = RoundRobinQueueSelection(), nextReceiver = exit
        )
        val classA = QObjectClass("ClassA", typeId = 1)
        val classB = QObjectClass("ClassB", typeId = 2)
        net.source("SrcA", ConstantRV(1.0), firstReceiver = nway.input(0), maxArrivals = 2, qObjectClass = classA)
        net.source(
            "SrcB", ConstantRV(1.0), firstReceiver = nway.input(1),
            timeUntilFirstRV = ConstantRV(1.5), maxArrivals = 1, qObjectClass = classB
        )
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()

        // a0 [1,3]; at t=3 round-robin (last served queue 0) picks queue 1 (b0) -> [3,5],
        // then a1 [5,7]. class B (b0) = 5-1.5 = 3.5; class A: a0=2, a1=7-2=5 -> 3.5
        assertEquals(3.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(3.5, net.classSystemTime("ClassB")!!.acrossReplicationStatistic.average, 1e-9)
        assertEquals(3.5, net.classSystemTime("ClassA")!!.acrossReplicationStatistic.average, 1e-9)
    }
}
