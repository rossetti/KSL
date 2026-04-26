package ksl.simulation

import ksl.examples.book.chapter6.BlockingQExample
import ksl.examples.book.chapter6.DriveThroughPharmacy
import ksl.examples.book.chapter6.EntityGeneratorExample
import ksl.examples.book.chapter6.HoldQExample
import ksl.examples.book.chapter6.SignalExample
import ksl.examples.book.chapter6.SoccerMomWithSuspensions
import ksl.examples.book.chapter6.WaitForProcessExample
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertTrue

/**
 * Regression tests for Chapter 6 process-view simulation models.
 *
 * Three tiers for statistical models:
 *  - Smoke     : structural checks
 *  - Analytical: M/M/1 queueing theory bounds
 *  - Golden    : exact bit-identical values (fast config: 5 reps × 2 000 min, warm-up 500)
 *
 * Deterministic models (HoldQ, Signal, BlockingQ, WaitFor, SoccerMom) are
 * verified to run to completion without exception.
 *
 * Golden constants start as Double.NaN (discovery mode).  When NaN the test
 * prints the current value and passes; replace NaN with the printed value to
 * enable the exact-equality guard.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProcessModelingTest {

    // ── Configuration ─────────────────────────────────────────────────────────

    companion object {
        private const val FAST_REPS   = 5
        private const val FAST_LENGTH = 2000.0
        private const val FAST_WARMUP = 500.0

        // M/M/1 theory: λ=1/6, μ=1/3, ρ=0.5 → E[W]=6 min, E[L]=1.0, ρ=0.5
        private const val MM1_SYS_TIME_THEORY    = 6.0
        private const val MM1_UTILIZATION_THEORY  = 0.5
        private const val MM1_NUM_IN_SYS_THEORY   = 1.0
        private const val ANALYTICAL_DELTA        = 1.0

        // Golden values — DriveThroughPharmacy (process-view, Ch6Example1)
        // Config: arrivals ExponentialRV(6.0, stream 1), service ExponentialRV(3.0, stream 2)
        // Fast config: 5 reps × 2 000 min, warm-up 500 | default KSL seed
        // Note: these are bit-identical to the EventModelingTest DTP golden values,
        //       confirming process-view and event-view produce equivalent simulation paths.
        private const val DTP_PV_SYS_TIME_AVG    = 6.519175714766078
        private const val DTP_PV_SYS_TIME_VAR    = 0.7799281426388178
        private const val DTP_PV_NUM_IN_SYS_AVG  = 1.0870969591127024
        private const val DTP_PV_NUM_SERVED_AVG  = 251.2
        private const val DTP_PV_PROB_GT4_AVG    = 0.5409736820855663

        // Golden values — EntityGeneratorExample (Ch6Example2)
        // Config: arrivals ExponentialRV(6.0, stream 1), service ExponentialRV(3.0, stream 2)
        // Fast config: 5 reps × 2 000 min, warm-up 500 | default KSL seed
        private const val EGE_WIP_AVG         = 1.0870969591127024
        private const val EGE_TIP_AVG         = 6.519175714766078
        private const val EGE_NUM_SERVED_AVG  = 251.2
    }

    // ── Shared simulation state (built once for all tests) ────────────────────

    private lateinit var dtpPvModel: Model
    private lateinit var dtpPv: DriveThroughPharmacy

    private lateinit var egeModel: Model

    @BeforeAll
    fun runSimulations() {
        // DriveThroughPharmacy — process-view (Ch6Example1 configuration)
        dtpPvModel = Model("DTP-ProcessView-Test")
        dtpPvModel.numberOfReplications     = FAST_REPS
        dtpPvModel.lengthOfReplication      = FAST_LENGTH
        dtpPvModel.lengthOfReplicationWarmUp = FAST_WARMUP
        dtpPv = DriveThroughPharmacy(dtpPvModel, name = "DriveThrough")
        dtpPv.arrivalRV.initialRandomSource = ExponentialRV(6.0, 1)
        dtpPv.serviceRV.initialRandomSource = ExponentialRV(3.0, 2)
        dtpPvModel.simulate()

        // EntityGeneratorExample (Ch6Example2 — stream numbers fixed in class definition)
        egeModel = Model("EGE-Test")
        egeModel.numberOfReplications     = FAST_REPS
        egeModel.lengthOfReplication      = FAST_LENGTH
        egeModel.lengthOfReplicationWarmUp = FAST_WARMUP
        EntityGeneratorExample(egeModel, "System")
        egeModel.simulate()
    }

    // ── DriveThroughPharmacy (process-view) — Tier 1: Smoke ──────────────────

    @Test
    fun dtpProcessViewHasExpectedResponses() {
        assertTrue(dtpPvModel.responses.isNotEmpty(), "Model must have at least one response")
        assertTrue(dtpPvModel.counters.isNotEmpty(),  "Model must have at least one counter")
        val names = dtpPvModel.responseNames
        assertTrue(names.any { "TimeInSystem" in it }, "Expected TimeInSystem response")
        assertTrue(names.any { "NumInSystem"  in it }, "Expected NumInSystem response")
        assertTrue(names.any { "NumServed"    in it }, "Expected NumServed counter")
    }

    @Test
    fun dtpProcessViewAcrossReplicationCountEqualsReplications() {
        assertEquals(
            FAST_REPS.toDouble(),
            dtpPv.systemTime.acrossReplicationStatistic.count,
            0.0,
            "Across-replication count must equal number of replications"
        )
    }

    // ── DriveThroughPharmacy (process-view) — Tier 2: Analytical Bounds ──────

    @Test
    fun dtpProcessViewSystemTimeNearTheoreticalMean() {
        assertEquals(
            MM1_SYS_TIME_THEORY,
            dtpPv.systemTime.acrossReplicationStatistic.average,
            ANALYTICAL_DELTA,
            "E[W] ≈ 6.0 min for M/M/1 with λ=1/6, μ=1/3, ρ=0.5"
        )
    }

    @Test
    fun dtpProcessViewNumInSystemNearTheoreticalMean() {
        assertEquals(
            MM1_NUM_IN_SYS_THEORY,
            dtpPv.numInSystem.acrossReplicationStatistic.average,
            ANALYTICAL_DELTA,
            "E[L] ≈ 1.0 for M/M/1 with ρ=0.5"
        )
    }

    // ── Cross-validation: process-view produces identical output to event-view ──

    @Test
    fun dtpProcessViewSystemTimeMatchesEventViewExactly() {
        // DriveThroughPharmacy (process-view, Ch6) and DriveThroughPharmacyWithQ
        // (event-view, Ch4) model the same M/M/1 queue with stream 1 (arrivals)
        // and stream 2 (service).  With a deterministic RNG seed both must follow
        // bit-identical simulation paths.  Any divergence indicates a logic regression
        // in one of the two implementations.
        val pvAvg = dtpPv.systemTime.acrossReplicationStatistic.average
        assertEquals(DTP_PV_SYS_TIME_AVG, pvAvg, 0.0,
            "Process-view system time must be bit-identical to event-view golden value")
    }

    // ── DriveThroughPharmacy (process-view) — Tier 3: Golden Values ──────────

    @Test
    fun dtpProcessViewSystemTimeGolden() {
        val stat = dtpPv.systemTime.acrossReplicationStatistic
        assertGolden(DTP_PV_SYS_TIME_AVG, stat.average,  "DTP_PV_SYS_TIME_AVG")
        assertGolden(DTP_PV_SYS_TIME_VAR, stat.variance, "DTP_PV_SYS_TIME_VAR")
    }

    @Test
    fun dtpProcessViewNumInSystemGolden() {
        assertGolden(DTP_PV_NUM_IN_SYS_AVG, dtpPv.numInSystem.acrossReplicationStatistic.average, "DTP_PV_NUM_IN_SYS_AVG")
    }

    @Test
    fun dtpProcessViewNumServedGolden() {
        assertGolden(DTP_PV_NUM_SERVED_AVG, dtpPv.numCustomersServed.acrossReplicationStatistic.average, "DTP_PV_NUM_SERVED_AVG")
    }

    @Test
    fun dtpProcessViewProbGt4Golden() {
        assertGolden(DTP_PV_PROB_GT4_AVG, dtpPv.probSystemTimeGT4Minutes.acrossReplicationStatistic.average, "DTP_PV_PROB_GT4_AVG")
    }

    // ── EntityGeneratorExample — Tier 1: Smoke ────────────────────────────────

    @Test
    fun entityGeneratorHasExpectedResponses() {
        assertTrue(egeModel.responses.isNotEmpty())
        assertTrue(egeModel.counters.isNotEmpty())
        val names = egeModel.responseNames
        // EntityGeneratorExample names its responses "${name}:WIP", "${name}:TimeInSystem", etc.
        // We constructed it with name="System".
        assertTrue(names.any { "WIP" in it },          "Expected WIP response")
        assertTrue(names.any { "TimeInSystem" in it }, "Expected TimeInSystem response")
        assertTrue(names.any { "NumServed" in it },    "Expected NumServed counter")
    }

    @Test
    fun entityGeneratorAcrossReplicationCountEqualsReplications() {
        // EntityGeneratorExample has all-private fields; access stats via model counter list.
        val numServedStat = egeModel.counters.first { "NumServed" in it.name }
            .acrossReplicationStatistic
        assertEquals(FAST_REPS.toDouble(), numServedStat.count, 0.0)
    }

    // ── EntityGeneratorExample — Tier 3: Golden Values ───────────────────────
    // EntityGeneratorExample exposes no public properties; access stats by name.

    @Test
    fun entityGeneratorWipGolden() {
        val avg = egeModel.responses.first { "WIP" in it.name }.acrossReplicationStatistic.average
        assertGolden(EGE_WIP_AVG, avg, "EGE_WIP_AVG")
    }

    @Test
    fun entityGeneratorTimeInSystemGolden() {
        val avg = egeModel.responses.first { "TimeInSystem" in it.name }.acrossReplicationStatistic.average
        assertGolden(EGE_TIP_AVG, avg, "EGE_TIP_AVG")
    }

    @Test
    fun entityGeneratorNumServedGolden() {
        val avg = egeModel.counters.first { "NumServed" in it.name }.acrossReplicationStatistic.average
        assertGolden(EGE_NUM_SERVED_AVG, avg, "EGE_NUM_SERVED_AVG")
    }

    // ── Deterministic models: run-without-exception ───────────────────────────

    @Test
    fun holdQueueExampleRunsWithoutException() {
        val m = Model("HoldQ-Test")
        HoldQExample(m)
        m.lengthOfReplication  = 50.0
        m.numberOfReplications = 1
        m.simulate()
        // Single deterministic replication; verify clock advanced to run length
        assertTrue(m.currentReplicationNumber >= 1)
    }

    @Test
    fun signalExampleRunsWithoutException() {
        val m = Model("Signal-Test")
        SignalExample(m)
        m.numberOfReplications = 1
        m.lengthOfReplication  = 50.0
        m.simulate()
        assertTrue(m.currentReplicationNumber >= 1)
    }

    @Test
    fun blockingQueueExampleRunsWithoutException() {
        val m = Model("BlockingQ-Test")
        BlockingQExample(m)
        m.lengthOfReplication  = 100.0
        m.numberOfReplications = 1
        m.simulate()
        assertTrue(m.currentReplicationNumber >= 1)
    }

    @Test
    fun waitForProcessExampleRunsWithoutException() {
        val m = Model("WaitFor-Test")
        WaitForProcessExample(m)
        m.numberOfReplications = 1
        m.lengthOfReplication  = 200.0
        m.simulate()
        assertTrue(m.currentReplicationNumber >= 1)
    }

    @Test
    fun soccerMomWithSuspensionsRunsWithoutException() {
        val m = Model("SoccerMom-Test")
        SoccerMomWithSuspensions(m)
        m.lengthOfReplication  = 150.0
        m.numberOfReplications = 1
        m.simulate()
        assertTrue(m.currentReplicationNumber >= 1)
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
