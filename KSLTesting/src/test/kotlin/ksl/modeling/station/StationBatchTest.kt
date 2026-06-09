package ksl.modeling.station

import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.ConstantRV

/**
 * Phase-3 (slice A) tests for [BatchStation] and [SeparateStation].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationBatchTest {

    @Test
    fun batchThenSeparateIsPopulationNeutral() {
        val m = Model("Batch", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val separate = net.separateStation("Separate", nextReceiver = exit)
        val batch = net.batchStation("Batch", batchSize = 3, nextReceiver = separate)
        net.source("Arrivals", ConstantRV(1.0), firstReceiver = batch, maxArrivals = 6)
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()

        // 6 arrivals -> 2 batches of 3 -> separated back into 6 members reaching the sink
        assertEquals(2.0, batch.numBatchesFormed.acrossReplicationStatistic.average, 1e-9)
        assertEquals(6.0, separate.numSeparated.acrossReplicationStatistic.average, 1e-9)
        assertEquals(6.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun incompleteBatchIsNotFormed() {
        val m = Model("PartialBatch", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val separate = net.separateStation("Separate", nextReceiver = exit)
        val batch = net.batchStation("Batch", batchSize = 4, nextReceiver = separate)
        net.source("Arrivals", ConstantRV(1.0), firstReceiver = batch, maxArrivals = 6)
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()

        // 6 arrivals, batch size 4 -> one batch of 4; the remaining 2 stay accumulating
        assertEquals(1.0, batch.numBatchesFormed.acrossReplicationStatistic.average, 1e-9)
        assertEquals(4.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
    }
}
