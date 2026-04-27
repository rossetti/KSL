package ksl.simulation

import ksl.examples.book.chapter7.StemFairMixerEnhancedSched
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for non-stationary arrivals with capacity scheduling.
 *
 * Model: StemFairMixerEnhancedSched (Ch 7) — a 6-hour career-fair mixer with:
 *   - Non-homogeneous Poisson arrivals (NHPP piecewise-constant rate function,
 *     peak ~60 students/hr at hours 4–5)
 *   - Two recruiters (JHBunt, MalWart) whose capacities follow CapacitySchedule
 *   - Process-model students who walk, chat, and visit recruiters
 *   - Door closes at t = lengthOfMixer − warningTime = 360 − 30 = 330 min
 *   - Terminating simulation: runs until all remaining students depart
 *
 * Three tiers:
 *  - Smoke     : run completes without error, response names exist
 *  - Analytical: ordering / sign constraints from queuing theory / model logic
 *  - Golden    : exact bit-identical replication averages
 *
 * Fast config: 10 reps × natural termination | default KSL seed.
 *
 * Golden constants start as Double.NaN (discovery mode).  Replace with the
 * printed value to enable the exact-equality guard.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NonStationaryArrivalsTest {

    // ── Configuration ─────────────────────────────────────────────────────────

    companion object {
        private const val FAST_REPS   = 10
        private const val WARNING_TIME = 30.0   // door closes at 360 - 30 = 330

        // Golden values — 10 reps × natural termination | default KSL seed
        private const val OVERALL_SYS_TIME_AVG       = 19.409317079180152
        private const val RECRUITING_SYS_TIME_AVG    = 12.892114402576118
        private const val MIXING_SYS_TIME_AVG        = 29.61820360919292
        private const val NUM_IN_SYSTEM_AVG          = 9.170733674554826
        private const val NUM_AT_JHBUNT_CLOSING_AVG  = 1.0
        private const val MIXER_ENDING_TIME_AVG      = 361.85807222905447
    }

    // ── Shared state ──────────────────────────────────────────────────────────

    private lateinit var model: Model
    private lateinit var mixer: StemFairMixerEnhancedSched

    @BeforeAll
    fun setup() {
        model = Model("SFSTest", autoCSVReports = false)
        mixer = StemFairMixerEnhancedSched(model, "SFS")
        mixer.warningTime = WARNING_TIME
        mixer.timeSeriesResponse.acrossRepStatisticsOption = true
        model.numberOfReplications = FAST_REPS
        model.simulate()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Average across replications for a named response (Response, TWResponse, AggregateTWResponse). */
    private fun avg(name: String): Double {
        val r = model.response(name) ?: error("No response named '$name' in model")
        return r.acrossReplicationStatistic.average
    }

    private fun assertGolden(expected: Double, actual: Double, name: String) {
        if (expected.isNaN()) {
            println("GOLDEN_DISCOVERY: $name = $actual")
            return
        }
        assertEquals(expected, actual, 0.0, "Golden regression failed for $name")
    }

    // ── Tier 1: Smoke ─────────────────────────────────────────────────────────

    @Test
    fun modelResponseNamesContainOverallSystemTime() {
        assertTrue(model.responseNames.any { "OverallSystemTime" in it },
            "Model must have an 'OverallSystemTime' response")
    }

    @Test
    fun modelResponseNamesContainRecruitingOnlySystemTime() {
        assertTrue(model.responseNames.any { "RecruitingOnlySystemTime" in it })
    }

    @Test
    fun modelResponseNamesContainMixingStudentSystemTime() {
        assertTrue(model.responseNames.any { "MixingStudentSystemTime" in it })
    }

    @Test
    fun modelResponseNamesContainNumInSystem() {
        assertTrue(model.responseNames.any { "NumInSystem" in it })
    }

    @Test
    fun modelResponseNamesContainMixerEndingTime() {
        assertTrue(model.responseNames.any { "Mixer Ending Time" in it })
    }

    @Test
    fun modelResponseNamesContainStudentsAtRecruiters() {
        assertTrue(model.responseNames.any { "StudentsAtRecruiters" in it })
    }

    @Test
    fun modelResponseNamesContainNumAtJHBuntAtClosing() {
        assertTrue(model.responseNames.any { "NumAtJHBuntAtClosing" in it })
    }

    @Test
    fun overallSystemTimeResponseIsNonNull() {
        assertNotNull(model.response("OverallSystemTime"))
    }

    @Test
    fun allResponsesHaveFiniteAcrossRepAverage() {
        for (r in model.simulationReporter.responses) {
            val a = r.acrossReplicationStatistic.average
            // NaN indicates no observations were collected (permissible for rarely-triggered responses)
            assertTrue(a.isFinite() || a.isNaN(),
                "${r.name}: across-replication average must be finite or NaN, got $a")
        }
    }

    // ── Tier 2: Analytical Bounds ─────────────────────────────────────────────

    @Test
    fun overallSystemTimeIsPositive() {
        assertTrue(avg("OverallSystemTime") > 0.0,
            "Average system time must be positive (students spend time in mixer)")
    }

    @Test
    fun overallSystemTimeExceedsMinimumWalkTime() {
        // Fastest walker: TriangularRV(88, 176, 264) → mode 176 ft/min
        // Shortest path (direct exit): walkToNameTags(20ft) + walkFromNameTagsToExit(140ft)
        // At mode 176 ft/min: (20+140)/176 ≈ 0.91 minutes minimum
        val minWalk = (20.0 + 140.0) / 264.0  // fastest possible walk speed
        assertTrue(avg("OverallSystemTime") > minWalk,
            "Average system time must exceed minimum walk time (${minWalk} min)")
    }

    @Test
    fun recruitingOnlySystemTimeIsPositive() {
        assertTrue(avg("RecruitingOnlySystemTime") > 0.0,
            "Recruiting-only system time must be positive")
    }

    @Test
    fun mixingStudentSystemTimeExceedsRecruitingOnlySystemTime() {
        // Mixing students walk to conversation area first, then to recruiting
        // → longer path than recruiting-only students
        val mixingAvg    = avg("MixingStudentSystemTime")
        val recruitingAvg = avg("RecruitingOnlySystemTime")
        assertTrue(mixingAvg > recruitingAvg,
            "Mixing students have a longer path than recruiting-only students: " +
            "mixing=$mixingAvg, recruiting=$recruitingAvg")
    }

    @Test
    fun numInSystemTimeAvgIsPositive() {
        assertTrue(avg("NumInSystem") > 0.0,
            "Time-weighted average number in system must be positive")
    }

    @Test
    fun mixerEndingTimeExceedsDoorClosingTime() {
        // Door closes at t=330 (lengthOfMixer=360 − warningTime=30).
        // Students still in system when door closes must depart afterward.
        val doorClose = mixer.doorClosingTime
        assertTrue(avg("Mixer Ending Time") > doorClose,
            "Mixer must end after door closing time ($doorClose); got ${avg("Mixer Ending Time")}")
    }

    @Test
    fun numAtJHBuntAtClosingIsNonNegative() {
        assertTrue(avg("NumAtJHBuntAtClosing") >= 0.0)
    }

    @Test
    fun numAtMalWartAtClosingIsNonNegative() {
        assertTrue(avg("NumAtMalWartAtClosing") >= 0.0)
    }

    @Test
    fun studentsAtRecruitersIsPositive() {
        assertTrue(avg("StudentsAtRecruiters") > 0.0,
            "Time-weighted average students at recruiters must be positive during the event")
    }

    // ── Tier 3: Golden Values ─────────────────────────────────────────────────

    @Test
    fun overallSystemTimeGolden() {
        assertGolden(OVERALL_SYS_TIME_AVG, avg("OverallSystemTime"), "OVERALL_SYS_TIME_AVG")
    }

    @Test
    fun recruitingOnlySystemTimeGolden() {
        assertGolden(RECRUITING_SYS_TIME_AVG, avg("RecruitingOnlySystemTime"), "RECRUITING_SYS_TIME_AVG")
    }

    @Test
    fun mixingStudentSystemTimeGolden() {
        assertGolden(MIXING_SYS_TIME_AVG, avg("MixingStudentSystemTime"), "MIXING_SYS_TIME_AVG")
    }

    @Test
    fun numInSystemGolden() {
        assertGolden(NUM_IN_SYSTEM_AVG, avg("NumInSystem"), "NUM_IN_SYSTEM_AVG")
    }

    @Test
    fun numAtJHBuntAtClosingGolden() {
        assertGolden(NUM_AT_JHBUNT_CLOSING_AVG, avg("NumAtJHBuntAtClosing"), "NUM_AT_JHBUNT_CLOSING_AVG")
    }

    @Test
    fun mixerEndingTimeGolden() {
        assertGolden(MIXER_ENDING_TIME_AVG, avg("Mixer Ending Time"), "MIXER_ENDING_TIME_AVG")
    }
}
