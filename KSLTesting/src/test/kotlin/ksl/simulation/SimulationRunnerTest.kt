package ksl.simulation

import kotlinx.coroutines.runBlocking
import ksl.controls.experiments.ConcurrentSimulationRunner
import ksl.controls.experiments.SimulationRun
import ksl.controls.experiments.SimulationRunner
import ksl.examples.book.appendixD.GIGcQueue
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for SimulationRunner.
 *
 * Model: GIGcQueue M/M/1 (λ=1, μ=2, ρ=0.5).
 * Streams: arrivals = ExponentialRV(mean=1.0, stream 1),
 *          service  = ExponentialRV(mean=0.5, stream 2).
 *
 * Fast config: 5 reps × 1 000 min, warm-up 200 | default KSL seed.
 *
 * Three tiers:
 *  - Smoke       : hasResults, hasError, responseCount, replication count
 *  - Structural  : chunkReplications utility (10 reps, chunk size 4 → 3 chunks)
 *  - Golden      : exact bit-identical System Time average for the default run
 *
 * Golden constants start as Double.NaN (discovery mode). Replace with the
 * printed value to enable the exact-equality guard.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimulationRunnerTest {

    // ── Configuration ─────────────────────────────────────────────────────────

    companion object {
        private const val FAST_REPS   = 5
        private const val FAST_LENGTH = 1000.0
        private const val FAST_WARMUP = 200.0

        // Golden value — GIGcQueue M/M/1, default streams
        // Fast config: 5 reps × 1 000 min, warm-up 200 | default KSL seed
        private const val DEFAULT_SYS_TIME_AVG = 1.0775437514262844
    }

    // ── Shared state ──────────────────────────────────────────────────────────

    private lateinit var model: Model
    private lateinit var defaultRun: SimulationRun

    @BeforeAll
    fun setup() {
        model = buildFastModel("SimRunTest")
        val runner = SimulationRunner(model)
        defaultRun = runner.simulate()
    }

    // ── Tier 1: Smoke ─────────────────────────────────────────────────────────

    @Test
    fun defaultRunHasResults() {
        assertTrue(defaultRun.hasResults, "SimulationRun must have results")
    }

    @Test
    fun defaultRunHasNoError() {
        assertFalse(defaultRun.hasError, "SimulationRun must not have a run error")
    }

    @Test
    fun defaultRunHasCorrectReplicationCount() {
        assertEquals(FAST_REPS, defaultRun.numberOfReplications)
    }

    @Test
    fun defaultRunContainsSystemTimeResponse() {
        assertTrue(
            defaultRun.responseNames.any { "System Time" in it },
            "SimulationRun results must contain a 'System Time' response"
        )
    }

    @Test
    fun defaultRunResponseCountIsPositive() {
        assertTrue(defaultRun.responseCount > 0, "Response count must be > 0")
    }

    // ── Tier 2: Structural — chunkReplications ────────────────────────────────

    @Test
    fun chunkReplicationsProducesThreeChunks() {
        val chunks = SimulationRunner.chunkReplications(model, 10, 4)
        assertEquals(3, chunks.size, "10 reps chunked by 4 must yield 3 chunks (4+4+2)")
    }

    @Test
    fun chunkReplicationsFirstChunkStartsAtRepOne() {
        val chunks = SimulationRunner.chunkReplications(model, 10, 4)
        assertEquals(1, chunks[0].startingRepId,         "First chunk must start at rep 1")
        assertEquals(4, chunks[0].numberOfReplications,  "First chunk must contain 4 reps")
    }

    @Test
    fun chunkReplicationsMiddleChunkStartsAtFive() {
        val chunks = SimulationRunner.chunkReplications(model, 10, 4)
        assertEquals(5, chunks[1].startingRepId,         "Second chunk must start at rep 5")
        assertEquals(4, chunks[1].numberOfReplications,  "Second chunk must contain 4 reps")
    }

    @Test
    fun chunkReplicationsLastChunkHasRemainder() {
        val chunks = SimulationRunner.chunkReplications(model, 10, 4)
        assertEquals(2, chunks.last().numberOfReplications,
            "Last chunk must contain 2 reps (10 mod 4 = 2)")
    }

    @Test
    fun chunkReplicationsTotalCountMatchesRequested() {
        val chunks = SimulationRunner.chunkReplications(model, 10, 4)
        val total = chunks.sumOf { it.numberOfReplications }
        assertEquals(10, total, "Sum of all chunk replication counts must equal 10")
    }

    @Test
    fun chunkReplicationsSingleChunkWhenSizeExceedsTotal() {
        val chunks = SimulationRunner.chunkReplications(model, 5, 10)
        assertEquals(1, chunks.size, "Chunk size > total reps must produce exactly 1 chunk")
        assertEquals(5, chunks[0].numberOfReplications)
    }

    // ── Tier 3: Golden Values ─────────────────────────────────────────────────

    @Test
    fun defaultRunSystemTimeGolden() {
        val avg = defaultRun.replicationObservations("System Time")!!.average()
        assertGolden(DEFAULT_SYS_TIME_AVG, avg, "DEFAULT_SYS_TIME_AVG")
    }

    @Test
    fun synchronousRunnerStillMatchesFreshModelRunAfterSupportRefactor() {
        val modelName = "SimRunSupportParity"
        val firstRun = SimulationRunner(buildFastModel(modelName)).simulate()
        val secondRun = SimulationRunner(buildFastModel(modelName)).simulate()

        assertArrayEquals(
            firstRun.replicationObservations("System Time")!!,
            secondRun.replicationObservations("System Time")!!,
            0.0,
            "SimulationRunner should remain deterministic across fresh equivalent models"
        )
    }

    @Test
    fun concurrentSimulationRunnerMatchesSynchronousRunnerForEquivalentModel() = runBlocking {
        val modelName = "ConcurrentSimRunParity"
        val synchronous = SimulationRunner(buildFastModel(modelName)).simulate()
        val concurrent = ConcurrentSimulationRunner(buildFastModel(modelName)).simulate()

        assertEquals(synchronous.numberOfReplications, concurrent.numberOfReplications)
        assertArrayEquals(
            synchronous.replicationObservations("System Time")!!,
            concurrent.replicationObservations("System Time")!!,
            0.0,
            "ConcurrentSimulationRunner should preserve SimulationRunner setup and result semantics"
        )
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun buildFastModel(modelName: String): Model {
        return Model(modelName, autoCSVReports = false).also { model ->
            model.numberOfReplications      = FAST_REPS
            model.lengthOfReplication       = FAST_LENGTH
            model.lengthOfReplicationWarmUp = FAST_WARMUP
            GIGcQueue(model, numServers = 1, name = "MM1Q")
        }
    }

    private fun assertGolden(expected: Double, actual: Double, name: String) {
        if (expected.isNaN()) {
            println("GOLDEN_DISCOVERY: $name = $actual")
            return
        }
        assertEquals(expected, actual, 0.0, "Golden regression failed for $name")
    }
}
