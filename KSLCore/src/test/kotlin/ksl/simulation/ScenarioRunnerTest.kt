package ksl.simulation

import ksl.controls.experiments.Scenario
import ksl.controls.experiments.ScenarioRunner
import ksl.examples.book.appendixD.GIGcQueue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for ScenarioRunner + Scenario.
 *
 * Two scenarios backed by independent GIGcQueue (M/M/c) model instances:
 *   OneServer : c=1, λ=1, μ=2, ρ=0.5  — M/M/1 queue
 *   TwoServers: c=2, λ=1, μ=2, ρ=0.25 — M/M/2 queue, shorter wait
 *
 * Streams: arrivals = ExponentialRV(mean=1.0, stream 1),
 *          service  = ExponentialRV(mean=0.5, stream 2).
 *
 * Fast config: 5 reps × 1 000 min, warm-up 200 | default KSL seed.
 *
 * Three tiers:
 *  - Smoke    : structural checks (scenario count, names, simulationRun presence)
 *  - Analytical: 2-server avg system time < 1-server avg system time
 *  - Golden   : exact bit-identical replication averages for System Time
 *
 * Golden constants start as Double.NaN (discovery mode). Replace with the
 * printed value to enable the exact-equality guard.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScenarioRunnerTest {

    // ── Configuration ─────────────────────────────────────────────────────────

    companion object {
        private const val FAST_REPS   = 5
        private const val FAST_LENGTH = 1000.0
        private const val FAST_WARMUP = 200.0

        // Golden values — GIGcQueue M/M/1 and M/M/2
        // Fast config: 5 reps × 1 000 min, warm-up 200 | default KSL seed
        private const val ONE_SERVER_SYS_TIME_AVG = 1.0775437514262844
        private const val TWO_SERVER_SYS_TIME_AVG = 0.5495790013482315
    }

    // ── Shared state ──────────────────────────────────────────────────────────

    private lateinit var runner: ScenarioRunner

    @BeforeAll
    fun runScenarios() {
        val model1 = Model("SR_1S", autoCSVReports = false)
        GIGcQueue(model1, numServers = 1, name = "MM1Q")

        val model2 = Model("SR_2S", autoCSVReports = false)
        GIGcQueue(model2, numServers = 2, name = "MM1Q")

        val s1 = Scenario(
            model = model1,
            name = "OneServer",
            numberReplications = FAST_REPS,
            lengthOfReplication = FAST_LENGTH,
            lengthOfReplicationWarmUp = FAST_WARMUP
        )
        val s2 = Scenario(
            model = model2,
            name = "TwoServers",
            numberReplications = FAST_REPS,
            lengthOfReplication = FAST_LENGTH,
            lengthOfReplicationWarmUp = FAST_WARMUP
        )
        runner = ScenarioRunner("ScenarioRunnerTest", listOf(s1, s2))
        runner.simulate()
    }

    // ── Tier 1: Smoke ─────────────────────────────────────────────────────────

    @Test
    fun scenarioRunnerHasTwoScenarios() {
        assertEquals(2, runner.scenarioList.size)
    }

    @Test
    fun scenariosFoundByName() {
        assertNotNull(runner.scenarioByName("OneServer"),  "Expected 'OneServer' scenario")
        assertNotNull(runner.scenarioByName("TwoServers"), "Expected 'TwoServers' scenario")
    }

    @Test
    fun allScenariosHaveSimulationRunsWithoutError() {
        for (s in runner.scenarioList) {
            val run = s.simulationRun
            assertNotNull(run, "Scenario '${s.name}' must have a simulationRun after simulate()")
            assertFalse(run.hasError, "Scenario '${s.name}' must not have a run error")
            assertTrue(run.hasResults, "Scenario '${s.name}' must have results")
        }
    }

    @Test
    fun allScenariosHaveCorrectReplicationCount() {
        for (s in runner.scenarioList) {
            assertEquals(
                FAST_REPS,
                s.simulationRun!!.numberOfReplications,
                "Scenario '${s.name}' must have $FAST_REPS replications"
            )
        }
    }

    @Test
    fun systemTimeResponsePresentInBothScenarios() {
        for (s in runner.scenarioList) {
            assertTrue(
                s.simulationRun!!.responseNames.any { "System Time" in it },
                "Scenario '${s.name}' must contain a 'System Time' response"
            )
        }
    }

    // ── Tier 2: Analytical Bounds ─────────────────────────────────────────────

    @Test
    fun twoServersFasterThanOneServer() {
        val oneAvg = runner.scenarioByName("OneServer")!!
            .simulationRun!!.replicationObservations("System Time")!!.average()
        val twoAvg = runner.scenarioByName("TwoServers")!!
            .simulationRun!!.replicationObservations("System Time")!!.average()
        assertTrue(
            twoAvg < oneAvg,
            "2-server avg system time ($twoAvg) must be less than 1-server ($oneAvg)"
        )
    }

    // ── Tier 3: Golden Values ─────────────────────────────────────────────────

    @Test
    fun oneServerSystemTimeGolden() {
        val avg = runner.scenarioByName("OneServer")!!
            .simulationRun!!.replicationObservations("System Time")!!.average()
        assertGolden(ONE_SERVER_SYS_TIME_AVG, avg, "ONE_SERVER_SYS_TIME_AVG")
    }

    @Test
    fun twoServerSystemTimeGolden() {
        val avg = runner.scenarioByName("TwoServers")!!
            .simulationRun!!.replicationObservations("System Time")!!.average()
        assertGolden(TWO_SERVER_SYS_TIME_AVG, avg, "TWO_SERVER_SYS_TIME_AVG")
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
