package ksl.simulation

import ksl.examples.book.chapter4.DriveThroughPharmacyWithQ
import ksl.examples.book.chapter4.TandemQueue
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertTrue

/**
 * Regression tests for Chapter 4 event-view simulation models.
 *
 * Three tiers per model:
 *  - Smoke     : structural checks (responses collected, counters non-empty)
 *  - Analytical: M/M/1 queueing theory bounds
 *  - Golden    : exact bit-identical values from a reference run
 *                (fast config: 5 reps × 2 000 min, warm-up 500)
 *
 * Golden constants start as Double.NaN (discovery mode).  When NaN the test
 * prints the current value and passes; replace NaN with the printed value to
 * enable the exact-equality guard.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventModelingTest {

    // ── Configuration ─────────────────────────────────────────────────────────

    companion object {
        private const val FAST_REPS   = 5
        private const val FAST_LENGTH = 2000.0
        private const val FAST_WARMUP = 500.0

        // M/M/1 theory: λ=1/6, μ=1/3, ρ=0.5 → E[W]=6 min, E[L]=1.0, ρ=0.5
        private const val MM1_SYS_TIME_THEORY   = 6.0
        private const val MM1_UTILIZATION_THEORY = 0.5
        private const val MM1_NUM_IN_SYS_THEORY  = 1.0
        private const val ANALYTICAL_DELTA       = 1.0  // wide for 5-rep sample

        // Golden values — DriveThroughPharmacyWithQ
        // Config: 1 server, arrivals ExponentialRV(6.0, stream 1), service ExponentialRV(3.0, stream 2)
        // Fast config: 5 reps × 2 000 min, warm-up 500 | default KSL seed
        private const val DTP_SYS_TIME_AVG   = 6.519175714766078
        private const val DTP_SYS_TIME_VAR   = 0.7799281426388178
        private const val DTP_NUM_BUSY_AVG   = 0.5171186327771773
        private const val DTP_NUM_IN_SYS_AVG = 1.0870969591127024
        private const val DTP_NUM_SERVED_AVG = 251.2
        private const val DTP_PROB_GT4_AVG   = 0.5409736820855663

        // Golden values — TandemQueue
        // Config: arrivals ExponentialRV(6.0, stream 1), station1 ExponentialRV(4.0, stream 2),
        //         station2 ExponentialRV(3.0, stream 3)
        // Fast config: 5 reps × 2 000 min, warm-up 500 | default KSL seed
        private const val TQ_SYS_TIME_AVG   = 20.216664504407564
        private const val TQ_SYS_TIME_VAR   = 2.0678421614960953
        private const val TQ_NUM_IN_SYS_AVG = 3.388071041328516
        private const val TQ_PROCESSED_AVG  = 252.4
    }

    // ── Shared simulation state (built once for all tests) ────────────────────

    private lateinit var dtpModel: Model
    private lateinit var dtp: DriveThroughPharmacyWithQ

    private lateinit var tqModel: Model
    private lateinit var tq: TandemQueue

    @BeforeAll
    fun runSimulations() {
        dtpModel = Model("DTP-EventTest")
        dtpModel.numberOfReplications    = FAST_REPS
        dtpModel.lengthOfReplication     = FAST_LENGTH
        dtpModel.lengthOfReplicationWarmUp = FAST_WARMUP
        dtp = DriveThroughPharmacyWithQ(dtpModel, 1)
        dtp.arrivalGenerator.setInitialEventTimeProcesses(ExponentialRV(6.0, 1))
        dtp.serviceRV.initialRandomSource = ExponentialRV(3.0, 2)
        dtpModel.simulate()

        tqModel = Model("TQ-EventTest")
        tqModel.numberOfReplications    = FAST_REPS
        tqModel.lengthOfReplication     = FAST_LENGTH
        tqModel.lengthOfReplicationWarmUp = FAST_WARMUP
        tq = TandemQueue(tqModel, name = "TandemQ")
        tqModel.simulate()
    }

    // ── DriveThroughPharmacyWithQ — Tier 1: Smoke ────────────────────────────

    @Test
    fun dtpHasExpectedResponsesAndCounters() {
        assertTrue(dtpModel.responses.isNotEmpty(), "Model must have at least one response")
        assertTrue(dtpModel.counters.isNotEmpty(),  "Model must have at least one counter")
        val names = dtpModel.responseNames
        assertTrue(names.any { "System Time" in it }, "Expected 'System Time' response")
        assertTrue(names.any { "NumBusy"     in it }, "Expected 'NumBusy' response")
        assertTrue(names.any { "Num in System" in it }, "Expected 'Num in System' response")
    }

    @Test
    fun dtpAcrossReplicationCountEqualsReplications() {
        assertEquals(
            FAST_REPS.toDouble(),
            dtp.systemTime.acrossReplicationStatistic.count,
            0.0,
            "Across-replication count must equal number of replications"
        )
    }

    @Test
    fun dtpCustomersServedIsPositive() {
        assertTrue(dtp.numCustomersServed.acrossReplicationStatistic.average > 0.0)
    }

    // ── DriveThroughPharmacyWithQ — Tier 2: Analytical Bounds ────────────────

    @Test
    fun dtpSystemTimeNearTheoreticalMean() {
        assertEquals(
            MM1_SYS_TIME_THEORY,
            dtp.systemTime.acrossReplicationStatistic.average,
            ANALYTICAL_DELTA,
            "E[W] ≈ 6.0 min for M/M/1 with λ=1/6, μ=1/3, ρ=0.5"
        )
    }

    @Test
    fun dtpServerUtilizationNearRho() {
        assertEquals(
            MM1_UTILIZATION_THEORY,
            dtp.numBusyPharmacists.acrossReplicationStatistic.average,
            ANALYTICAL_DELTA,
            "Utilization ≈ 0.5 for ρ = λ/μ = 0.5"
        )
    }

    @Test
    fun dtpNumInSystemNearTheoreticalMean() {
        assertEquals(
            MM1_NUM_IN_SYS_THEORY,
            dtp.numInSystem.acrossReplicationStatistic.average,
            ANALYTICAL_DELTA,
            "E[L] ≈ 1.0 for M/M/1 with ρ=0.5"
        )
    }

    // ── DriveThroughPharmacyWithQ — Tier 3: Golden Values ────────────────────

    @Test
    fun dtpSystemTimeGolden() {
        val stat = dtp.systemTime.acrossReplicationStatistic
        assertGolden(DTP_SYS_TIME_AVG, stat.average,  "DTP_SYS_TIME_AVG")
        assertGolden(DTP_SYS_TIME_VAR, stat.variance, "DTP_SYS_TIME_VAR")
    }

    @Test
    fun dtpServerUtilizationGolden() {
        assertGolden(DTP_NUM_BUSY_AVG, dtp.numBusyPharmacists.acrossReplicationStatistic.average, "DTP_NUM_BUSY_AVG")
    }

    @Test
    fun dtpNumInSystemGolden() {
        assertGolden(DTP_NUM_IN_SYS_AVG, dtp.numInSystem.acrossReplicationStatistic.average, "DTP_NUM_IN_SYS_AVG")
    }

    @Test
    fun dtpNumServedGolden() {
        assertGolden(DTP_NUM_SERVED_AVG, dtp.numCustomersServed.acrossReplicationStatistic.average, "DTP_NUM_SERVED_AVG")
    }

    @Test
    fun dtpProbSystemTimeGt4Golden() {
        assertGolden(DTP_PROB_GT4_AVG, dtp.probSystemTimeGT4Minutes.acrossReplicationStatistic.average, "DTP_PROB_GT4_AVG")
    }

    // ── TandemQueue — Tier 1: Smoke ───────────────────────────────────────────

    @Test
    fun tandemQueueHasExpectedResponsesAndCounters() {
        assertTrue(tqModel.responses.isNotEmpty())
        assertTrue(tqModel.counters.isNotEmpty())
        assertTrue(tqModel.responseNames.any { "TotalSystemTime" in it })
    }

    @Test
    fun tandemQueueAcrossReplicationCountEqualsReplications() {
        assertEquals(
            FAST_REPS.toDouble(),
            tq.totalSystemTime.acrossReplicationStatistic.count,
            0.0
        )
    }

    // ── TandemQueue — Tier 2: Analytical Bounds ───────────────────────────────

    @Test
    fun tandemQueueSystemTimeExceedsComponentServiceTimes() {
        // Two M/M/1 stages in tandem: λ=1/6, μ₁=1/4 (mean 4), μ₂=1/3 (mean 3).
        // E[W] > E[S₁] + E[S₂] = 7 min minimum (queueing adds to service times).
        val avg = tq.totalSystemTime.acrossReplicationStatistic.average
        assertTrue(avg > 7.0, "Total system time must exceed sum of mean service times (4 + 3 = 7 min)")
    }

    @Test
    fun tandemQueueTotalProcessedIsPositive() {
        assertTrue(tq.totalProcessed.acrossReplicationStatistic.average > 0.0)
    }

    // ── TandemQueue — Tier 3: Golden Values ───────────────────────────────────

    @Test
    fun tandemQueueSystemTimeGolden() {
        val stat = tq.totalSystemTime.acrossReplicationStatistic
        assertGolden(TQ_SYS_TIME_AVG, stat.average,  "TQ_SYS_TIME_AVG")
        assertGolden(TQ_SYS_TIME_VAR, stat.variance, "TQ_SYS_TIME_VAR")
    }

    @Test
    fun tandemQueueNumInSystemGolden() {
        assertGolden(TQ_NUM_IN_SYS_AVG, tq.numInSystem.acrossReplicationStatistic.average, "TQ_NUM_IN_SYS_AVG")
    }

    @Test
    fun tandemQueueTotalProcessedGolden() {
        assertGolden(TQ_PROCESSED_AVG, tq.totalProcessed.acrossReplicationStatistic.average, "TQ_PROCESSED_AVG")
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Asserts exact equality when [expected] is not NaN.
     * When NaN (discovery mode) prints the current value and passes, allowing
     * the developer to copy the output into the companion constant.
     */
    private fun assertGolden(expected: Double, actual: Double, name: String) {
        if (expected.isNaN()) {
            println("GOLDEN_DISCOVERY: $name = $actual")
            return
        }
        assertEquals(expected, actual, 0.0, "Golden regression failed for $name")
    }
}
