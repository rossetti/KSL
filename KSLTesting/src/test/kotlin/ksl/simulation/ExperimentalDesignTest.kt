package ksl.simulation

import ksl.controls.experiments.*
import ksl.examples.book.appendixD.GIGcQueue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for the experimental-design classes in
 * ksl.controls.experiments (Factor, FactorialDesign, TwoLevelFactorialDesign,
 * DesignedExperiment).
 *
 * Two groups:
 *
 * 1. **Structural (no simulation)** — verifies design algebra: correct
 *    point counts, coded-value mappings, and half-fraction sizes.  These
 *    tests run independently of the @BeforeAll simulation setup so they
 *    cannot be broken by model errors.
 *
 * 2. **DOE simulation** — runs a 2² DesignedExperiment on a GIGcQueue
 *    model (M/M/c queue) with two factors:
 *      Server  : numServers ∈ {1, 2}   (control key: "MM1Q.numServers")
 *      MeanST  : service mean ∈ {0.5, 0.8}
 *                (RV key: "DOETest:ServiceTime.mean")
 *    4 design points × 3 reps = 12 simulation runs.
 *
 *    M/M/1 streams: arrivals = ExponentialRV(mean=1.0, stream 1),
 *                   service  = ExponentialRV(mean=0.5, stream 2).
 *    Fast config: 3 reps per design point, run length 1 000 min,
 *    warm-up 200 | default KSL seed.
 *
 *    Three tiers:
 *     - Smoke    : numSimulationRuns == 4, all runs succeeded, response names
 *     - Analytical: 2-server system time < 1-server at same service time
 *     - Golden   : exact bit-identical System Time averages for each design point
 *
 * Golden constants start as Double.NaN (discovery mode).  Replace with the
 * printed value to enable the exact-equality guard.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExperimentalDesignTest {

    // ── Configuration ─────────────────────────────────────────────────────────

    companion object {
        // Factor levels used in the 2² DOE
        private const val SERVER_LOW  = 1.0
        private const val SERVER_HIGH = 2.0
        private const val ST_LOW      = 0.5
        private const val ST_HIGH     = 0.8

        private const val DOE_REPS_PER_POINT = 3
        private const val DOE_LENGTH         = 1000.0
        private const val DOE_WARMUP         = 200.0

        // Golden values — 2² DOE, GIGcQueue
        // Fast config: 3 reps per design point × 1 000 min, warm-up 200 | default KSL seed
        // Server=1, MeanST=0.5  (ρ=0.5)
        private const val DP_1S_LOW_ST_AVG  = 1.1141502822993112
        // Server=2, MeanST=0.5  (ρ=0.25)
        private const val DP_2S_LOW_ST_AVG  = 0.5577292714848193
        // Server=1, MeanST=0.8  (ρ=0.8)
        private const val DP_1S_HIGH_ST_AVG = 3.644442661939545
        // Server=2, MeanST=0.8  (ρ=0.4)
        private const val DP_2S_HIGH_ST_AVG = 0.9829829400317086
    }

    // ── Shared DOE state ──────────────────────────────────────────────────────

    private lateinit var de: DesignedExperiment

    @BeforeAll
    fun runDesignedExperiment() {
        val fServer = TwoLevelFactor("Server", SERVER_LOW, SERVER_HIGH)
        val fST     = TwoLevelFactor("MeanST", ST_LOW,    ST_HIGH)
        val design  = TwoLevelFactorialDesign(setOf(fServer, fST))

        // Model name "DOETest" → stored as "DOETest" (no spaces to replace).
        // GIGcQueue named "MM1Q" uses parent.name = "DOETest" in its RV constructors,
        // so service-time RV name = "DOETest:ServiceTime" → key = "DOETest:ServiceTime.mean".
        val factors = mapOf(
            fServer to "MM1Q.numServers",
            fST     to "DOETest:ServiceTime.mean"
        )
        val model = Model("DOETest", autoCSVReports = false)
        model.lengthOfReplication       = DOE_LENGTH
        model.lengthOfReplicationWarmUp = DOE_WARMUP
        GIGcQueue(model, numServers = 1, name = "MM1Q")
        de = DesignedExperiment("DOETest_DE", model, factors, design)
        de.simulateAll(numRepsPerDesignPoint = DOE_REPS_PER_POINT)
    }

    // ── Group 1: Structural (no simulation) ───────────────────────────────────

    @Test
    fun factorFromIntProgressionHasCorrectLevelCount() {
        val f = Factor("A", 1..5 step 1)
        assertEquals(5, f.levels.size)
    }

    @Test
    fun factorialDesignHasCorrectPointCount() {
        val fA = Factor("A", 1..3 step 1)
        val fB = Factor("B", doubleArrayOf(0.5, 1.0))
        val fd = FactorialDesign(setOf(fA, fB))
        assertEquals(6, fd.numDesignPoints, "3×2 factorial must have 6 design points")
    }

    @Test
    fun twoLevelFactorialDesignHasCorrectPointCount() {
        val tld = TwoLevelFactorialDesign(setOf(
            TwoLevelFactor("A", 1.0, 3.0),
            TwoLevelFactor("B", 2.0, 5.0),
            TwoLevelFactor("C", 4.0, 8.0)
        ))
        assertEquals(8, tld.numDesignPoints, "2³ design must have 8 points")
    }

    @Test
    fun halfFractionIteratorHasHalfTheFullDesignPoints() {
        val tld = TwoLevelFactorialDesign(setOf(
            TwoLevelFactor("A", 1.0, 3.0),
            TwoLevelFactor("B", 2.0, 5.0),
            TwoLevelFactor("C", 4.0, 8.0)
        ))
        val halfItr = tld.halfFractionIterator()
        assertEquals(4, halfItr.numPoints, "Half-fraction of 2³ must have 4 points")
    }

    @Test
    fun twoLevelFactorCodedLevelsAreMinusOneAndOne() {
        val f = TwoLevelFactor("X", 5.0, 15.0)
        val coded = f.codedLevels()
        assertEquals(-1.0, coded[0], 0.0, "Low level must code to -1")
        assertEquals( 1.0, coded[1], 0.0, "High level must code to +1")
    }

    @Test
    fun factorIsInRangeReturnsTrueForMidpoint() {
        val f = TwoLevelFactor("X", 4.0, 16.0)
        assertTrue(f.isInRange(10.0), "Midpoint must be in range")
    }

    @Test
    fun factorIsInRangeReturnsFalseOutsideBounds() {
        val f = TwoLevelFactor("X", 4.0, 16.0)
        assertFalse(f.isInRange(3.0),  "Value below low must be out of range")
        assertFalse(f.isInRange(17.0), "Value above high must be out of range")
    }

    @Test
    fun twoToKDesignHasCorrectPointCount() {
        val kd = FactorialDesign.twoToKDesign(setOf("A", "B", "C", "D"))
        assertEquals(16, kd.numDesignPoints, "2⁴ design must have 16 points")
    }

    // ── Group 2: DOE Simulation — Tier 1: Smoke ───────────────────────────────

    @Test
    fun designedExperimentHasFourSimulationRuns() {
        assertEquals(4, de.numSimulationRuns, "2² design must produce 4 simulation runs")
    }

    @Test
    fun allDesignPointRunsSucceeded() {
        for (run in de.simulationRuns) {
            assertFalse(run.hasError,   "Run '${run.name}' must not have a run error")
            assertTrue(run.hasResults,  "Run '${run.name}' must have results")
        }
    }

    @Test
    fun responseNamesContainSystemTime() {
        assertTrue(
            de.responseNames.any { "System Time" in it },
            "DesignedExperiment must expose a 'System Time' response"
        )
    }

    @Test
    fun eachRunHasCorrectReplicationCount() {
        for (run in de.simulationRuns) {
            assertEquals(
                DOE_REPS_PER_POINT,
                run.numberOfReplications,
                "Run '${run.name}' must have $DOE_REPS_PER_POINT replications"
            )
        }
    }

    // ── Group 2: DOE Simulation — Tier 2: Analytical Bounds ──────────────────

    @Test
    fun twoServersHasLowerSystemTimeThanOneServerAtLowServiceTime() {
        val one = dpAvg(SERVER_LOW,  ST_LOW)
        val two = dpAvg(SERVER_HIGH, ST_LOW)
        assertTrue(
            two < one,
            "2-server avg system time ($two) must be less than 1-server ($one) at MeanST=$ST_LOW"
        )
    }

    @Test
    fun higherServiceTimeMeanIncreaseSystemTime() {
        val lowST  = dpAvg(SERVER_LOW, ST_LOW)
        val highST = dpAvg(SERVER_LOW, ST_HIGH)
        assertTrue(
            highST > lowST,
            "Higher mean service time ($highST) must increase system time vs lower ($lowST)"
        )
    }

    // ── Group 2: DOE Simulation — Tier 3: Golden Values ──────────────────────

    @Test
    fun dp1ServerLowSTGolden() {
        assertGolden(DP_1S_LOW_ST_AVG,  dpAvg(SERVER_LOW,  ST_LOW),  "DP_1S_LOW_ST_AVG")
    }

    @Test
    fun dp2ServerLowSTGolden() {
        assertGolden(DP_2S_LOW_ST_AVG,  dpAvg(SERVER_HIGH, ST_LOW),  "DP_2S_LOW_ST_AVG")
    }

    @Test
    fun dp1ServerHighSTGolden() {
        assertGolden(DP_1S_HIGH_ST_AVG, dpAvg(SERVER_LOW,  ST_HIGH), "DP_1S_HIGH_ST_AVG")
    }

    @Test
    fun dp2ServerHighSTGolden() {
        assertGolden(DP_2S_HIGH_ST_AVG, dpAvg(SERVER_HIGH, ST_HIGH), "DP_2S_HIGH_ST_AVG")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dpAvg(servers: Double, meanST: Double): Double {
        val run = de.simulationRuns.first {
            it.inputs["MM1Q.numServers"]           == servers &&
            it.inputs["DOETest:ServiceTime.mean"]  == meanST
        }
        return run.replicationObservations("System Time")!!.average()
    }

    private fun assertGolden(expected: Double, actual: Double, name: String) {
        if (expected.isNaN()) {
            println("GOLDEN_DISCOVERY: $name = $actual")
            return
        }
        assertEquals(expected, actual, 0.0, "Golden regression failed for $name")
    }
}
