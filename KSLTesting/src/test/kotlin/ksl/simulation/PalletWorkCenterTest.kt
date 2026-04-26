package ksl.simulation

import ksl.examples.book.chapter5.PalletWorkCenter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertTrue

/**
 * Regression tests for PalletWorkCenter (Chapter 5).
 *
 * Three test groups:
 *
 * 1. **Single-run golden values** — one experiment, 2 workers, fast config
 *    (5 reps × 480 min, no warm-up).  Smoke, analytical bounds, and exact
 *    bit-identical golden values.
 *
 * 2. **Independent experiments (no CRN)** — two sequential experiments on the
 *    same model (2 workers → 3 workers) without resetting streams.  Verifies
 *    the expected ordering (3 workers produces lower processing time) and
 *    captures golden values for each experiment.
 *
 * 3. **Common Random Numbers (CRN)** — same two-experiment design with
 *    `model.resetStartStreamOption = true`.  Verifies CRN runs correctly,
 *    preserves the ordering, and confirms that the first experiment result
 *    is bit-identical to the IND first experiment (stream state is the same
 *    for the first experiment regardless of CRN setting).
 *
 * Golden constants start as Double.NaN (discovery mode).  When NaN the test
 * prints the current value and passes; replace NaN with the printed value to
 * enable the exact-equality guard.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PalletWorkCenterTest {

    // ── Configuration ─────────────────────────────────────────────────────────

    companion object {
        // PalletWorkCenter is a terminating simulation: each replication ends naturally
        // when all pallets (BinomialRV mean ≈ 80) have been processed.  No fixed
        // lengthOfReplication is set — the model default (Double.POSITIVE_INFINITY)
        // applies and the executive halts when the event calendar is empty.
        // Fast config: 5 reps, no warm-up.
        private const val FAST_REPS   = 5
        private const val FAST_WARMUP = 0.0

        // ── Golden values: single run, 2 workers ──────────────────────────────
        // Streams: numPallets=BinomialRV(0.8,100,stream 1), transport=Exp(5,stream 2),
        //          processing=Triangular(8,12,15,stream 3)
        // Fast config: 5 reps, natural termination | default KSL seed
        private const val SINGLE_SYS_TIME_AVG        = 49.665504520740484
        private const val SINGLE_SYS_TIME_VAR        = 331.69902773891323
        private const val SINGLE_TOTAL_PROC_AVG      = 495.1370292705993
        private const val SINGLE_TOTAL_PROC_VAR      = 969.6111605694548
        private const val SINGLE_NUM_PROCESSED_AVG   = 79.6
        private const val SINGLE_UTIL_AVG            = 0.9417789842059965
        private const val SINGLE_PROB_OVERTIME_AVG   = 0.6

        // ── Golden values: IND experiments ────────────────────────────────────
        // Fast config: 5 reps, natural termination | default KSL seed
        private const val IND_2W_TOTAL_PROC_AVG      = 495.1370292705993
        private const val IND_3W_TOTAL_PROC_AVG      = 390.25078864594207

        // ── Golden values: CRN experiments ────────────────────────────────────
        // Fast config: 5 reps, natural termination, resetStartStreamOption=true | default KSL seed
        private const val CRN_2W_TOTAL_PROC_AVG      = 495.1370292705993
        private const val CRN_3W_TOTAL_PROC_AVG      = 431.4413431830633
    }

    // ── Shared simulation state ───────────────────────────────────────────────

    // Group 1: single run
    private lateinit var singleRunPwc: PalletWorkCenter

    // Group 2: IND experiments
    private var ind2wAvg = Double.NaN
    private var ind3wAvg = Double.NaN

    // Group 3: CRN experiments
    private var crn2wAvg = Double.NaN
    private var crn3wAvg = Double.NaN

    @BeforeAll
    fun runSimulations() {
        // ── Group 1: single run, 2 workers ────────────────────────────────────
        val singleModel = Model("PWC-Single")
        singleModel.numberOfReplications = FAST_REPS
        singleRunPwc = PalletWorkCenter(singleModel, numWorkers = 2)
        singleModel.simulate()

        // ── Group 2: IND experiments (2 workers → 3 workers) ─────────────────
        val indModel = Model("PWC-IND")
        indModel.numberOfReplications = FAST_REPS
        val indPwc = PalletWorkCenter(indModel, numWorkers = 2)

        indModel.experimentName = "TwoWorkers"
        indModel.simulate()
        ind2wAvg = indPwc.totalProcessingTime.acrossReplicationStatistic.average

        indModel.experimentName = "ThreeWorkers"
        indPwc.numWorkers = 3
        indModel.simulate()
        ind3wAvg = indPwc.totalProcessingTime.acrossReplicationStatistic.average

        // ── Group 3: CRN experiments (resetStartStreamOption = true) ──────────
        val crnModel = Model("PWC-CRN")
        crnModel.numberOfReplications   = FAST_REPS
        crnModel.resetStartStreamOption = true
        val crnPwc = PalletWorkCenter(crnModel, numWorkers = 2)

        crnModel.experimentName = "TwoWorkers_CRN"
        crnModel.simulate()
        crn2wAvg = crnPwc.totalProcessingTime.acrossReplicationStatistic.average

        crnModel.experimentName = "ThreeWorkers_CRN"
        crnPwc.numWorkers = 3
        crnModel.simulate()
        crn3wAvg = crnPwc.totalProcessingTime.acrossReplicationStatistic.average
    }

    // ── Group 1: Single run — Tier 1: Smoke ──────────────────────────────────

    @Test
    fun singleRunHasExpectedResponsesAndCounters() {
        val model = singleRunPwc.model
        assertTrue(model.responses.isNotEmpty())
        assertTrue(model.counters.isNotEmpty())
        val names = model.responseNames
        assertTrue(names.any { "System Time"         in it })
        assertTrue(names.any { "Total Processing"    in it })
        assertTrue(names.any { "Num Processed"       in it })
        assertTrue(names.any { "Worker Utilization"  in it })
    }

    @Test
    fun singleRunAcrossReplicationCountEqualsReplications() {
        assertEquals(
            FAST_REPS.toDouble(),
            singleRunPwc.systemTime.acrossReplicationStatistic.count,
            0.0
        )
    }

    // ── Group 1: Single run — Tier 2: Analytical Bounds ──────────────────────

    @Test
    fun singleRunTotalProcessingTimeIsPositive() {
        assertTrue(singleRunPwc.totalProcessingTime.acrossReplicationStatistic.average > 0.0)
    }

    @Test
    fun singleRunWorkerUtilizationIsBetweenZeroAndOne() {
        val util = singleRunPwc.workerUtilization.acrossReplicationStatistic.average
        assertTrue(util in 0.0..1.0, "Worker utilization must be in [0,1]; got $util")
    }

    @Test
    fun singleRunProbOvertimeIsBetweenZeroAndOne() {
        val p = singleRunPwc.probOfOverTime.acrossReplicationStatistic.average
        assertTrue(p in 0.0..1.0, "P{overtime} must be in [0,1]; got $p")
    }

    @Test
    fun singleRunNumProcessedIsPositive() {
        assertTrue(singleRunPwc.numPalletsProcessed.acrossReplicationStatistic.average > 0.0)
    }

    // ── Group 1: Single run — Tier 3: Golden Values ───────────────────────────

    @Test
    fun singleRunSystemTimeGolden() {
        val stat = singleRunPwc.systemTime.acrossReplicationStatistic
        assertGolden(SINGLE_SYS_TIME_AVG, stat.average,  "SINGLE_SYS_TIME_AVG")
        assertGolden(SINGLE_SYS_TIME_VAR, stat.variance, "SINGLE_SYS_TIME_VAR")
    }

    @Test
    fun singleRunTotalProcessingTimeGolden() {
        val stat = singleRunPwc.totalProcessingTime.acrossReplicationStatistic
        assertGolden(SINGLE_TOTAL_PROC_AVG, stat.average,  "SINGLE_TOTAL_PROC_AVG")
        assertGolden(SINGLE_TOTAL_PROC_VAR, stat.variance, "SINGLE_TOTAL_PROC_VAR")
    }

    @Test
    fun singleRunNumProcessedGolden() {
        assertGolden(SINGLE_NUM_PROCESSED_AVG, singleRunPwc.numPalletsProcessed.acrossReplicationStatistic.average, "SINGLE_NUM_PROCESSED_AVG")
    }

    @Test
    fun singleRunWorkerUtilizationGolden() {
        assertGolden(SINGLE_UTIL_AVG, singleRunPwc.workerUtilization.acrossReplicationStatistic.average, "SINGLE_UTIL_AVG")
    }

    @Test
    fun singleRunProbOvertimeGolden() {
        assertGolden(SINGLE_PROB_OVERTIME_AVG, singleRunPwc.probOfOverTime.acrossReplicationStatistic.average, "SINGLE_PROB_OVERTIME_AVG")
    }

    // ── Group 2: IND experiments ──────────────────────────────────────────────

    @Test
    fun indThreeWorkersFasterThanTwoWorkers() {
        assertTrue(
            ind3wAvg < ind2wAvg,
            "3-worker total processing time ($ind3wAvg) must be less than 2-worker ($ind2wAvg)"
        )
    }

    @Test
    fun indExperimentsGoldenValues() {
        assertGolden(IND_2W_TOTAL_PROC_AVG, ind2wAvg, "IND_2W_TOTAL_PROC_AVG")
        assertGolden(IND_3W_TOTAL_PROC_AVG, ind3wAvg, "IND_3W_TOTAL_PROC_AVG")
    }

    // ── Group 3: CRN experiments ──────────────────────────────────────────────

    @Test
    fun crnThreeWorkersFasterThanTwoWorkers() {
        assertTrue(
            crn3wAvg < crn2wAvg,
            "CRN 3-worker total processing time ($crn3wAvg) must be less than 2-worker ($crn2wAvg)"
        )
    }

    @Test
    fun crnFirstExperimentMatchesIndFirstExperiment() {
        // With CRN the first experiment starts from the same stream state as without CRN,
        // so the 2-worker results must be bit-identical between the two experiment modes.
        assertEquals(
            ind2wAvg, crn2wAvg, 0.0,
            "CRN 2-worker result must equal IND 2-worker result (same initial stream state)"
        )
    }

    @Test
    fun crnSecondExperimentDiffersFromIndSecondExperiment() {
        // With CRN, streams are reset before the second experiment so it starts from
        // the same state as the first experiment.  Without CRN, streams continue from
        // where the first experiment left off.  The 3-worker averages must therefore differ.
        assertTrue(
            crn3wAvg != ind3wAvg,
            "CRN 3-worker result must differ from IND 3-worker result " +
            "(CRN resets streams; IND continues from prior experiment's stream state)"
        )
    }

    @Test
    fun crnExperimentsGoldenValues() {
        assertGolden(CRN_2W_TOTAL_PROC_AVG, crn2wAvg, "CRN_2W_TOTAL_PROC_AVG")
        assertGolden(CRN_3W_TOTAL_PROC_AVG, crn3wAvg, "CRN_3W_TOTAL_PROC_AVG")
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun assertGolden(expected: Double, actual: Double, name: String) {
        if (expected.isNaN()) {
            println("GOLDEN_DISCOVERY: $name = $actual")
            return
        }
        assertEquals(expected, actual, 0.0, "Golden regression failed for $name")
    }
}
