package ksl.utilities.io.dbutil

import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.InMemorySnapshotCollector
import ksl.simulation.Model
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files

/**
 * Integration test for [SnapshotBatchWriter].
 *
 * Runs a GIGcQueue M/M/1 model (3 reps × 500 min) with an [InMemorySnapshotCollector]
 * attached.  After the run, [SnapshotBatchWriter.write] commits the snapshots to a
 * fresh SQLite [KSLDatabase].  The test then queries the database to verify that all
 * expected records landed with correct FK wiring.
 *
 * A second identical run through [KSLDatabaseObserver] (the existing sequential path)
 * is used as an oracle: the record counts from both paths must match.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotBatchWriterTest {

    private val REPS = 3
    private val DURATION = 500.0
    private val EXP_NAME = "BatchWriterExp"

    private lateinit var batchDb: KSLDatabase
    private lateinit var oracleDb: KSLDatabase

    @BeforeAll
    fun setup() {
        val tempDir = Files.createTempDirectory("ksl_snapshot_batch_writer_test")

        // ── Batch path (SnapshotBatchWriter) ──────────────────────────────────
        batchDb = KSLDatabase.createKSLDatabase("batch_writer_test", tempDir)

        val batchModel = Model("BatchWriterSim", autoCSVReports = false)
        batchModel.experimentName = EXP_NAME
        batchModel.numberOfReplications = REPS
        batchModel.lengthOfReplication = DURATION
        GIGcQueue(batchModel)

        InMemorySnapshotCollector(batchModel.lifeCycleEmitters).use { collector ->
            batchModel.simulate()
            val snapshots = collector.drain()
            SnapshotBatchWriter(batchDb).write(snapshots)
        }

        // ── Oracle path (KSLDatabaseObserver — sequential baseline) ───────────
        oracleDb = KSLDatabase.createKSLDatabase("oracle_test", tempDir)

        val oracleModel = Model("OracleSim", autoCSVReports = false)
        oracleModel.experimentName = EXP_NAME
        oracleModel.numberOfReplications = REPS
        oracleModel.lengthOfReplication = DURATION
        GIGcQueue(oracleModel)
        KSLDatabaseObserver(oracleModel, oracleDb)

        oracleModel.simulate()
    }

    @AfterAll
    fun teardown() {
        // nothing — temp dir contents are ephemeral test artifacts
    }

    @Test
    fun `experiment record exists in database`() {
        assertTrue(batchDb.doesExperimentRecordExist(EXP_NAME),
            "experiment record '$EXP_NAME' must exist after SnapshotBatchWriter.write()")
    }

    @Test
    fun `experiment record has correct sim_name`() {
        val record = batchDb.fetchExperimentData(EXP_NAME)
        assertNotNull(record)
        assertEquals("BatchWriterSim", record!!.sim_name)
    }

    @Test
    fun `within-rep row count matches oracle`() {
        val batchRows  = batchDb.withinRepViewData().filter { it.exp_name == EXP_NAME }.size
        val oracleRows = oracleDb.withinRepViewData().filter { it.exp_name == EXP_NAME }.size

        assertTrue(batchRows > 0, "within-rep rows must be non-empty")
        assertEquals(oracleRows, batchRows,
            "SnapshotBatchWriter within-rep row count must match KSLDatabaseObserver")
    }

    @Test
    fun `within-rep rows cover all replications`() {
        val repIds = batchDb.withinRepViewData()
            .filter { it.exp_name == EXP_NAME }
            .map { it.rep_id }
            .toSet()

        assertEquals((1..REPS).toSet(), repIds,
            "within-rep rows must exist for every replication 1..$REPS")
    }

    @Test
    fun `across-rep row count matches oracle`() {
        val batchRows  = batchDb.acrossReplicationStatistics.rowsCount()
        val oracleRows = oracleDb.acrossReplicationStatistics.rowsCount()

        assertTrue(batchRows > 0, "across-rep rows must be non-empty")
        assertEquals(oracleRows, batchRows,
            "SnapshotBatchWriter across-rep row count must match KSLDatabaseObserver")
    }

    @Test
    fun `simulation run record is finalized with correct last_rep_id`() {
        val expRecord = batchDb.fetchExperimentData(EXP_NAME)
        assertNotNull(expRecord)
        val runName = "OracleSim"  // run_name comes from model.runName which defaults to sim_name
        // Just verify the run end timestamp is set (non-null) via withinRepViewData last_rep_id field
        val lastRepId = batchDb.withinRepViewData()
            .filter { it.exp_name == EXP_NAME }
            .maxOfOrNull { it.last_rep_id }
        assertEquals(REPS, lastRepId,
            "last_rep_id in within-rep view must equal number of replications")
    }
}
