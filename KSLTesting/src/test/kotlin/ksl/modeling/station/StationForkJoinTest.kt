package ksl.modeling.station

import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.ConstantRV

/**
 * Phase-3 extension tests for [ForkStation] + [JoinStation]: a parent forks a
 * variable number of children, the parent and the children traverse independent
 * sub-paths, and the join releases the parent only after all of *its* children
 * arrive — regardless of arrival order.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationForkJoinTest {

    /**
     * Reads the child count from the parent's `qObjectType` for tests. Production
     * code would typically subclass QObject or use the marking hook.
     */
    private val countFromType = ChildCountIfc { p -> p.qObjectType }

    @Test
    fun parentForksThenJoinsExactlyItsChildren() {
        val m = Model("ForkJoin", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")

        val joinSt = net.joinStation("Join", nextReceiver = exit)
        val childMaker = net.singleQStation("ChildMaker", ConstantRV(2.0), capacity = 10, nextReceiver = joinSt.childInput())
        val paperwork = net.singleQStation("Paperwork", ConstantRV(5.0), nextReceiver = joinSt.parentInput())
        val fork = net.forkStation(
            "Fork", joinSt, countFromType,
            childReceiver = childMaker, nextReceiver = paperwork
        )

        // 2 orders: sizes 3 and 5 (set via qObjectType)
        var count = 0
        val sizes = intArrayOf(3, 5)
        net.source(
            "Arrivals", ConstantRV(50.0), firstReceiver = fork, maxArrivals = 2,
            timeUntilFirstRV = ConstantRV(1.0),
            marking = { q -> q.qObjectType = sizes[count++] }
        )

        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()

        // 2 orders forked, 3+5=8 children created
        assertEquals(2.0, fork.numForked.acrossReplicationStatistic.average, 1e-9)
        assertEquals(8.0, fork.numChildrenCreated.acrossReplicationStatistic.average, 1e-9)
        // both parents joined and reached the sink
        assertEquals(2.0, joinSt.numJoined.acrossReplicationStatistic.average, 1e-9)
        assertEquals(2.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)

        // timing for order 1 (arrives t=1, size=3):
        //   paperwork: serve 1..6 (parent at join at t=6)
        //   child maker (capacity 10): all 3 children serve [1,3] -> at join at t=3
        //   parent emits at max(6, last child) = 6 -> system time = 5
        // order 2 (arrives t=51, size=5): same shape, system time = 5
        assertEquals(5.0, net.systemTime.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun joinReleasesWhenChildrenArriveAfterParent() {
        // children take longer than the parent path; the join must hold the parent
        val m = Model("ForkJoinSlowChildren", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")

        val joinSt = net.joinStation("Join", nextReceiver = exit)
        val childMaker = net.singleQStation("ChildMaker", ConstantRV(4.0), capacity = 10, nextReceiver = joinSt.childInput())
        val paperwork = net.singleQStation("Paperwork", ConstantRV(1.0), nextReceiver = joinSt.parentInput())
        val fork = net.forkStation(
            "Fork", joinSt, countFromType,
            childReceiver = childMaker, nextReceiver = paperwork
        )

        net.source(
            "Arrivals", ConstantRV(100.0), firstReceiver = fork, maxArrivals = 1,
            timeUntilFirstRV = ConstantRV(1.0),
            marking = { q -> q.qObjectType = 2 }
        )

        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()

        // order arrives t=1, paperwork done at t=2 (parent at join @ t=2)
        // children done at t=5 (both arrive at join @ t=5) -> emit parent at t=5
        // system time = 4
        assertEquals(1.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(4.0, net.systemTime.acrossReplicationStatistic.average, 1e-9)
    }
}
