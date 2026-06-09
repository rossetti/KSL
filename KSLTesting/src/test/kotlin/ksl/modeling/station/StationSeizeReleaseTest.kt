package ksl.modeling.station

import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.ConstantRV
import kotlin.test.assertTrue

/**
 * Phase-3 extension tests for [SeizeStation] / [ReleaseStation] — the Arena-style
 * atomic operations that let an entity hold multiple resources simultaneously.
 *
 * Validation hooks: a [SinkStation] receiving an entity with outstanding
 * allocations fails loudly; an end-of-replication diagnostic exposes how many
 * entities still held allocations at run end (work-in-process).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationSeizeReleaseTest {

    @Test
    fun nestedSeizeHoldsBothResourcesAcrossDelay() {
        // Pattern: seize tester, seize machine, delay (process), release machine, release tester.
        // While the delay runs, BOTH tester and machine are held. A second arriving entity must
        // wait at the tester seize until the first releases.
        val m = Model("NestedSeize", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")

        val tester = net.resource("Tester", capacity = 1)
        val machine = net.resource("Machine", capacity = 1)

        val releaseTester = net.releaseStation("ReleaseTester", tester, nextReceiver = exit)
        val releaseMachine = net.releaseStation("ReleaseMachine", machine, nextReceiver = releaseTester)
        val delay = net.activityStation("Delay", ConstantRV(5.0), nextReceiver = releaseMachine)
        val seizeMachine = net.seizeStation("SeizeMachine", machine, nextReceiver = delay)
        val seizeTester = net.seizeStation("SeizeTester", tester, nextReceiver = seizeMachine)

        // 2 arrivals at t=1 and t=2; each one's flow takes 5 units of delay holding both resources.
        // With capacity 1 on tester, c2 waits until c1 finishes the whole nested sequence at t=6.
        // c2 starts at t=6, finishes at t=11. system times = 5 and 9 -> average 7.
        net.source("Arrivals", ConstantRV(1.0), firstReceiver = seizeTester, maxArrivals = 2)
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()

        assertEquals(2.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(7.0, net.systemTime.acrossReplicationStatistic.average, 1e-9)
        // tester and machine each seized once per entity
        assertEquals(2.0, seizeTester.numSeized.acrossReplicationStatistic.average, 1e-9)
        assertEquals(2.0, seizeMachine.numSeized.acrossReplicationStatistic.average, 1e-9)
        assertEquals(2.0, releaseMachine.numReleased.acrossReplicationStatistic.average, 1e-9)
        assertEquals(2.0, releaseTester.numReleased.acrossReplicationStatistic.average, 1e-9)
        // no leaks at end of run
        assertEquals(0.0, net.holdingsAtRunEnd.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun sharedResourceAcrossSeizeStations() {
        // Two distinct seize stations share one resource (cap 1); each station has
        // its own queue. An entity from either station holds the resource until
        // release. This is the "paperwork OR packaging" pattern where one resource
        // serves multiple distinct usage points.
        val m = Model("Shared", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")

        val packager = net.resource("Packager", capacity = 1)
        val releasePack = net.releaseStation("ReleasePack", packager, nextReceiver = exit)
        val packDelay = net.activityStation("PackDelay", ConstantRV(3.0), nextReceiver = releasePack)
        val seizePack = net.seizeStation("SeizePack", packager, nextReceiver = packDelay)

        // 2 entities arriving simultaneously at t=1 and t=2; one shared resource forces serial service.
        // c1: seize at 1, delay 1..4, release at 4 -> system time 3
        // c2: arrives t=2, queues; seize at 4, delay 4..7, release at 7 -> system time 5
        // average system time = (3+5)/2 = 4
        net.source("Arrivals", ConstantRV(1.0), firstReceiver = seizePack, maxArrivals = 2)
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()

        assertEquals(2.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(4.0, net.systemTime.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun exitWithUnreleasedAllocationFailsLoudly() {
        // The model "forgets" to release; the sink fires the validation error.
        val m = Model("Leak", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val r = net.resource("R", capacity = 1)
        val delay = net.activityStation("Delay", ConstantRV(1.0), nextReceiver = exit) // skipped release!
        val seize = net.seizeStation("SeizeR", r, nextReceiver = delay)
        net.source("Arrivals", ConstantRV(1.0), firstReceiver = seize, maxArrivals = 1)
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0

        val thrown = assertThrows(IllegalStateException::class.java) { m.simulate() }
        assertTrue(thrown.message!!.contains("Exit"), "error should name exit station: ${thrown.message}")
        assertTrue(thrown.message!!.contains("R"), "error should name held resource: ${thrown.message}")
    }

    @Test
    fun releaseWithoutSeizeFailsLoudly() {
        val m = Model("UnpairedRelease", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val r = net.resource("R", capacity = 1)
        val release = net.releaseStation("ReleaseR", r, nextReceiver = exit)
        net.source("Arrivals", ConstantRV(1.0), firstReceiver = release, maxArrivals = 1)
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0

        val thrown = assertThrows(IllegalStateException::class.java) { m.simulate() }
        assertTrue(thrown.message!!.contains("ReleaseR"), "error should name release station: ${thrown.message}")
        assertTrue(thrown.message!!.contains("R"), "error should name resource: ${thrown.message}")
    }
}
