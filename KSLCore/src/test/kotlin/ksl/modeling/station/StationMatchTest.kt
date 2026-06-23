package ksl.modeling.station

import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.ConstantRV

/**
 * Phase-3 (slice F) tests for [MatchStation]: assembling instances from multiple
 * inputs, both unkeyed (one from each) and keyed (by attribute).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationMatchTest {

    @Test
    fun unkeyedAssemblyMatchesOneFromEach() {
        val m = Model("Match", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val match = net.matchStation("Assemble", numInputs = 2, nextReceiver = exit)
        // input 0 gets 3 parts, input 1 gets 2 parts -> 2 assemblies (limited by input 1)
        net.source("PartA", ConstantRV(1.0), firstReceiver = match.input(0), maxArrivals = 3)
        net.source(
            "PartB", ConstantRV(1.0), firstReceiver = match.input(1),
            timeUntilFirstRV = ConstantRV(1.5), maxArrivals = 2
        )
        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0
        m.simulate()

        assertEquals(2.0, match.numMatched.acrossReplicationStatistic.average, 1e-9)
        assertEquals(2.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9) // 2 products reach the sink
    }

    @Test
    fun keyedMatchPairsByType() {
        val m = Model("KeyedMatch", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val match = net.matchStation("Assemble", numInputs = 2, keyExtractor = { it.qObjectType }, nextReceiver = exit)

        // input 0 receives type 1 then type 2; input 1 receives type 2 then type 1.
        // keyed matching pairs by type across inputs (not by arrival order).
        val seq0 = intArrayOf(1, 2)
        var i0 = 0
        net.source("In0", ConstantRV(1.0), firstReceiver = match.input(0), maxArrivals = 2,
            marking = { q -> q.qObjectType = seq0[i0++] })
        val seq1 = intArrayOf(2, 1)
        var i1 = 0
        net.source("In1", ConstantRV(1.0), firstReceiver = match.input(1), maxArrivals = 2,
            timeUntilFirstRV = ConstantRV(1.5),
            marking = { q -> q.qObjectType = seq1[i1++] })
        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0
        m.simulate()

        // type-2 pair (In0's 2nd, In1's 1st) and type-1 pair (In0's 1st, In1's 2nd) both match
        assertEquals(2.0, match.numMatched.acrossReplicationStatistic.average, 1e-9)
        assertEquals(2.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
    }
}
