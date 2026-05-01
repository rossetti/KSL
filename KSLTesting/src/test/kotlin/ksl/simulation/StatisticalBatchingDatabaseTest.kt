package ksl.simulation

import ksl.examples.book.chapter4.DriveThroughPharmacyWithQ
import ksl.utilities.io.dbutil.BatchStatTableData
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.KSLDatabaseObserver
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.BatchStatisticIfc
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Sequential database capture tests for StatisticalBatchingElement.
 *
 * This verifies the existing KSLDatabaseObserver path before adding concurrent
 * runner batch-stat parity checks.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatisticalBatchingDatabaseTest {

    companion object {
        private const val EXP_NAME = "DTP-StatisticalBatching-DB-Exp"
        private const val BATCH_LENGTH = 10_000.0
        private const val BATCH_WARMUP = 2_500.0
        private const val BATCH_INTERVAL = 10.0
    }

    private lateinit var model: Model
    private lateinit var dtp: DriveThroughPharmacyWithQ
    private lateinit var batching: StatisticalBatchingElement
    private lateinit var kslDb: KSLDatabase

    @BeforeAll
    fun runSimulationWithDatabaseObserver() {
        val tempDir = Files.createTempDirectory("ksl_statistical_batching_db_test")
        kslDb = KSLDatabase.createKSLDatabase("statistical_batching_test", tempDir)

        model = Model("DTP-StatisticalBatching-DB-Test", autoCSVReports = false)
        model.experimentName = EXP_NAME
        model.numberOfReplications = 1
        model.lengthOfReplication = BATCH_LENGTH
        model.lengthOfReplicationWarmUp = BATCH_WARMUP

        dtp = DriveThroughPharmacyWithQ(model, 1)
        dtp.arrivalGenerator.setInitialEventTimeProcesses(ExponentialRV(6.0, 1))
        dtp.serviceRV.initialRandomSource = ExponentialRV(3.0, 2)

        batching = model.statisticalBatching(BATCH_INTERVAL)
        KSLDatabaseObserver(model, kslDb)
        model.simulate()
    }

    @Test
    fun batchStatRowsExistForBatchedResponses() {
        val rows = kslDb.batchStatDataFor(EXP_NAME)
        val elementNames = elementNamesById()
        val batchedNames = rows.map { elementNames.getValue(it.element_id_fk) }.toSet()

        assertFalse(rows.isEmpty(), "Expected batch_stat rows for statistical batching")
        assertEquals(
            model.responses.size,
            rows.size,
            "Database should contain one batch_stat row for each model Response and TWResponse"
        )
        assertTrue("System Time" in batchedNames, "Expected System Time batch_stat row")
        assertTrue("SysTime >= 4 minutes" in batchedNames, "Expected indicator response batch_stat row")
        assertTrue("NumBusy" in batchedNames, "Expected NumBusy batch_stat row")
        assertTrue("Num in System" in batchedNames, "Expected Num in System batch_stat row")
    }

    @Test
    fun batchStatRowsMatchInMemoryBatchStatistics() {
        val rowsByElementId = kslDb.batchStatDataFor(EXP_NAME)
            .associateBy { it.element_id_fk }

        val expectedStats = batching.allResponseBatchStatisticsAsMap +
            batching.allTimeWeightedBatchStatisticsAsMap

        assertEquals(expectedStats.size, rowsByElementId.size)
        for ((response, stat) in expectedStats) {
            val row = rowsByElementId[response.id]
            assertNotNull(row, "Expected batch_stat row for '${response.name}'")
            assertBatchStatRowMatches(stat, row!!)
        }
    }

    @Test
    fun batchStatRowsUseReplicationOneAndSuccessfulRun() {
        val run = kslDb.simulationRunDataFor(EXP_NAME).single()
        assertEquals(1, run.num_reps)
        assertEquals(1, run.last_rep_id)
        assertTrue(run.run_error_msg.isNullOrBlank(), "Simulation run should not have an error message")

        val rows = kslDb.batchStatDataFor(EXP_NAME)
        assertTrue(rows.all { it.rep_id == 1 }, "Single-rep batching rows should be associated with rep_id = 1")
        assertTrue(
            rows.all { it.sim_run_id_fk == run.run_id },
            "All batch_stat rows should reference the experiment's simulation run"
        )
    }

    @Test
    fun countersAreNotPersistedAsBatchStats() {
        val elementNames = elementNamesById()
        val batchedNames = kslDb.batchStatDataFor(EXP_NAME)
            .map { elementNames.getValue(it.element_id_fk) }
            .toSet()

        assertFalse("Num Served" in batchedNames, "Counters should not be written to batch_stat")
        val counterIds = model.counters.map { it.id }.toSet()
        val batchedIds = kslDb.batchStatDataFor(EXP_NAME).map { it.element_id_fk }.toSet()
        assertTrue(
            batchedIds.intersect(counterIds).isEmpty(),
            "No counter element id should appear in batch_stat"
        )
    }

    private fun elementNamesById(): Map<Int, String> {
        return kslDb.modelElementDataFor(EXP_NAME)
            .associate { it.element_id to it.element_name }
    }

    private fun assertBatchStatRowMatches(stat: BatchStatisticIfc, row: BatchStatTableData) {
        assertEquals(stat.name, row.stat_name)
        assertEquals(stat.count, requireValue(row.stat_count, stat.name, "stat_count"), 0.0)
        assertEquals(stat.average, requireValue(row.average, stat.name, "average"), 0.0)
        assertEquals(stat.standardDeviation, requireValue(row.std_dev, stat.name, "std_dev"), 0.0)
        assertEquals(stat.minBatchSize.toDouble(), requireValue(row.min_batch_size, stat.name, "min_batch_size"), 0.0)
        assertEquals(stat.minNumBatches.toDouble(), requireValue(row.min_num_batches, stat.name, "min_num_batches"), 0.0)
        assertEquals(
            stat.minNumBatchesMultiple.toDouble(),
            requireValue(row.max_num_batches_multiple, stat.name, "max_num_batches_multiple"),
            0.0
        )
        assertEquals(stat.maxNumBatches.toDouble(), requireValue(row.max_num_batches, stat.name, "max_num_batches"), 0.0)
        assertEquals(stat.numRebatches.toDouble(), requireValue(row.num_rebatches, stat.name, "num_rebatches"), 0.0)
        assertEquals(
            stat.currentBatchSize.toDouble(),
            requireValue(row.current_batch_size, stat.name, "current_batch_size"),
            0.0
        )
        assertEquals(stat.amountLeftUnbatched, requireValue(row.amt_unbatched, stat.name, "amt_unbatched"), 0.0)
        assertEquals(
            stat.totalNumberOfObservations,
            requireValue(row.total_num_obs, stat.name, "total_num_obs"),
            0.0
        )
    }

    private fun requireValue(value: Double?, statName: String, columnName: String): Double {
        return requireNotNull(value) {
            "Expected non-null $columnName for batch statistic '$statName'"
        }
    }
}
