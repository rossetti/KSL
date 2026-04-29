package ksl.simulation

import ksl.controls.experiments.ConcurrentScenarioRunner
import ksl.controls.experiments.ExperimentRunParameters
import ksl.controls.experiments.Scenario
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.random.rvariable.ExponentialRV
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for [ConcurrentScenarioRunner].
 *
 * Three M/M/c scenarios (c = 1, 2, 3) are executed concurrently.  Each scenario uses a
 * dedicated [ModelBuilderIfc] that constructs a completely fresh [Model] on every build()
 * call.  To guarantee independent random streams under concurrent execution, each builder
 * uses a distinct pair of stream numbers (11/12, 13/14, 15/16), avoiding the shared-stream
 * race that would occur if multiple concurrent models requested the same stream number.
 *
 * Verification tiers:
 *  - **Smoke** — structural checks (scenario count, simulationRun presence, no errors)
 *  - **Analytical** — c=3 avg system time < c=2 < c=1 (M/M/c ordering property)
 *  - **DB** — [ConcurrentScenarioRunner.kslDb] has one experiment per scenario
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrentScenarioRunnerTest {

    // ── Configuration ─────────────────────────────────────────────────────────

    companion object {
        private const val FAST_REPS   = 5
        private const val FAST_LENGTH = 1000.0
        private const val FAST_WARMUP = 200.0

        // Streams chosen to be far from the default range used elsewhere in the test suite.
        private const val STREAM_S1_ARRIVE = 11
        private const val STREAM_S1_SERVE  = 12
        private const val STREAM_S2_ARRIVE = 13
        private const val STREAM_S2_SERVE  = 14
        private const val STREAM_S3_ARRIVE = 15
        private const val STREAM_S3_SERVE  = 16
    }

    // ── Shared state ──────────────────────────────────────────────────────────

    private lateinit var runner: ConcurrentScenarioRunner

    @BeforeAll
    fun runScenarios() {
        // Each builder produces a fully independent Model for every build() call.
        val builder1 = object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ksl.simulation.ExperimentRunParametersIfc?
            ): Model {
                val m = Model("CSR_1S", autoCSVReports = false)
                GIGcQueue(
                    m, numServers = 1,
                    ad = ExponentialRV(1.0, STREAM_S1_ARRIVE),
                    sd = ExponentialRV(0.5, STREAM_S1_SERVE),
                    name = "MM1Q"
                )
                return m
            }
        }

        val builder2 = object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ksl.simulation.ExperimentRunParametersIfc?
            ): Model {
                val m = Model("CSR_2S", autoCSVReports = false)
                GIGcQueue(
                    m, numServers = 2,
                    ad = ExponentialRV(1.0, STREAM_S2_ARRIVE),
                    sd = ExponentialRV(0.5, STREAM_S2_SERVE),
                    name = "MM1Q"
                )
                return m
            }
        }

        val builder3 = object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ksl.simulation.ExperimentRunParametersIfc?
            ): Model {
                val m = Model("CSR_3S", autoCSVReports = false)
                GIGcQueue(
                    m, numServers = 3,
                    ad = ExponentialRV(1.0, STREAM_S3_ARRIVE),
                    sd = ExponentialRV(0.5, STREAM_S3_SERVE),
                    name = "MM1Q"
                )
                return m
            }
        }

        val s1 = Scenario(
            modelBuilder = builder1,
            name = "OneServer",
            numberReplications = FAST_REPS,
            lengthOfReplication = FAST_LENGTH,
            lengthOfReplicationWarmUp = FAST_WARMUP
        )
        val s2 = Scenario(
            modelBuilder = builder2,
            name = "TwoServers",
            numberReplications = FAST_REPS,
            lengthOfReplication = FAST_LENGTH,
            lengthOfReplicationWarmUp = FAST_WARMUP
        )
        val s3 = Scenario(
            modelBuilder = builder3,
            name = "ThreeServers",
            numberReplications = FAST_REPS,
            lengthOfReplication = FAST_LENGTH,
            lengthOfReplicationWarmUp = FAST_WARMUP
        )

        runner = ConcurrentScenarioRunner("ConcurrentScenarioRunnerTest", listOf(s1, s2, s3))
        runBlocking { runner.simulate() }
    }

    // ── Tier 1: Smoke ─────────────────────────────────────────────────────────

    @Test
    fun runnerHasThreeScenarios() {
        assertEquals(3, runner.scenarioList.size)
    }

    @Test
    fun scenariosFoundByName() {
        assertNotNull(runner.scenarioByName("OneServer"),    "Expected 'OneServer'")
        assertNotNull(runner.scenarioByName("TwoServers"),   "Expected 'TwoServers'")
        assertNotNull(runner.scenarioByName("ThreeServers"), "Expected 'ThreeServers'")
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
    fun systemTimeResponsePresentInAllScenarios() {
        for (s in runner.scenarioList) {
            assertTrue(
                s.simulationRun!!.responseNames.any { "System Time" in it },
                "Scenario '${s.name}' must contain a 'System Time' response"
            )
        }
    }

    @Test
    fun allScenariosHaveFinitePositiveSystemTimeAverage() {
        for (s in runner.scenarioList) {
            val avg = s.simulationRun!!.replicationObservations("System Time")!!.average()
            assertTrue(avg.isFinite() && avg > 0.0,
                "Scenario '${s.name}' System Time average must be finite and positive (was $avg)")
        }
    }

    // ── Tier 2: Analytical ────────────────────────────────────────────────────

    @Test
    fun moreServersMeansLowerAverageSystemTime() {
        fun avg(name: String) = runner.scenarioByName(name)!!
            .simulationRun!!.replicationObservations("System Time")!!.average()

        val one   = avg("OneServer")
        val two   = avg("TwoServers")
        val three = avg("ThreeServers")

        assertTrue(two   < one,   "2-server avg ($two) must be < 1-server avg ($one)")
        assertTrue(three < two,   "3-server avg ($three) must be < 2-server avg ($two)")
    }

    // ── Tier 3: Database ──────────────────────────────────────────────────────

    @Test
    fun dbContainsOneExperimentPerScenario() {
        val expNames = runner.kslDb.experimentNames
        assertEquals(
            runner.scenarioList.size, expNames.size,
            "DB must contain exactly one experiment per scenario. Found: $expNames"
        )
    }

    @Test
    fun dbExperimentNamesMatchScenarioNames() {
        val expNames = runner.kslDb.experimentNames.toSet()
        for (s in runner.scenarioList) {
            assertTrue(s.name in expNames,
                "DB must contain an experiment named '${s.name}'. Found: $expNames")
        }
    }

    @Test
    fun observationsAsMapReturnsDataForSystemTime() {
        val map = runner.observationsAsMap("System Time")
        assertEquals(runner.scenarioList.size, map.size,
            "observationsAsMap must return an entry for every scenario")
        for ((name, obs) in map) {
            assertEquals(FAST_REPS, obs.size,
                "Scenario '$name' must have $FAST_REPS observations in the map")
        }
    }
}
