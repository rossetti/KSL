package ksl.simulation

import ksl.controls.experiments.ConcurrentScenarioRunner
import ksl.controls.experiments.Scenario
import ksl.controls.experiments.ScenarioRunner
import ksl.examples.book.appendixD.GIGcQueue
import ksl.utilities.random.rvariable.ExponentialRV
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
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
        private const val INTENTIONAL_FAILURE_MESSAGE = "Intentional failure for Phase 4"

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

    private fun buildQueueScenario(
        scenarioName: String,
        modelName: String,
        numServers: Int,
        arrivalStream: Int,
        serviceStream: Int,
        numberReplications: Int = FAST_REPS,
        lengthOfReplication: Double = FAST_LENGTH,
        lengthOfReplicationWarmUp: Double = FAST_WARMUP
    ): Scenario {
        val builder = object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model {
                val m = Model(modelName, autoCSVReports = false)
                GIGcQueue(
                    m,
                    numServers = numServers,
                    ad = ExponentialRV(1.0, arrivalStream),
                    sd = ExponentialRV(0.5, serviceStream),
                    name = "MM1Q"
                )
                return m
            }
        }

        return Scenario(
            modelBuilder = builder,
            name = scenarioName,
            numberReplications = numberReplications,
            lengthOfReplication = lengthOfReplication,
            lengthOfReplicationWarmUp = lengthOfReplicationWarmUp
        )
    }

    private fun buildThreeQueueScenarios(
        numberReplications: Int = FAST_REPS,
        lengthOfReplication: Double = FAST_LENGTH,
        lengthOfReplicationWarmUp: Double = FAST_WARMUP
    ): List<Scenario> {
        return listOf(
            buildQueueScenario(
                scenarioName = "OneServer",
                modelName = "CSR_1S",
                numServers = 1,
                arrivalStream = STREAM_S1_ARRIVE,
                serviceStream = STREAM_S1_SERVE,
                numberReplications = numberReplications,
                lengthOfReplication = lengthOfReplication,
                lengthOfReplicationWarmUp = lengthOfReplicationWarmUp
            ),
            buildQueueScenario(
                scenarioName = "TwoServers",
                modelName = "CSR_2S",
                numServers = 2,
                arrivalStream = STREAM_S2_ARRIVE,
                serviceStream = STREAM_S2_SERVE,
                numberReplications = numberReplications,
                lengthOfReplication = lengthOfReplication,
                lengthOfReplicationWarmUp = lengthOfReplicationWarmUp
            ),
            buildQueueScenario(
                scenarioName = "ThreeServers",
                modelName = "CSR_3S",
                numServers = 3,
                arrivalStream = STREAM_S3_ARRIVE,
                serviceStream = STREAM_S3_SERVE,
                numberReplications = numberReplications,
                lengthOfReplication = lengthOfReplication,
                lengthOfReplicationWarmUp = lengthOfReplicationWarmUp
            )
        )
    }

    private fun buildThreeScenarioRunner(
        runnerName: String = "ConcurrentScenarioRunnerTest_${System.nanoTime()}",
        numberReplications: Int = FAST_REPS,
        lengthOfReplication: Double = FAST_LENGTH,
        lengthOfReplicationWarmUp: Double = FAST_WARMUP
    ): ConcurrentScenarioRunner {
        return ConcurrentScenarioRunner(
            runnerName,
            buildThreeQueueScenarios(
                numberReplications = numberReplications,
                lengthOfReplication = lengthOfReplication,
                lengthOfReplicationWarmUp = lengthOfReplicationWarmUp
            )
        )
    }

    private fun runThreeScenarioRunner(
        runnerName: String = "ConcurrentScenarioRunnerTest_${System.nanoTime()}",
        numberReplications: Int = FAST_REPS,
        lengthOfReplication: Double = FAST_LENGTH,
        lengthOfReplicationWarmUp: Double = FAST_WARMUP
    ): ConcurrentScenarioRunner {
        val runner = buildThreeScenarioRunner(
            runnerName = runnerName,
            numberReplications = numberReplications,
            lengthOfReplication = lengthOfReplication,
            lengthOfReplicationWarmUp = lengthOfReplicationWarmUp
        )
        runBlocking { runner.simulate() }
        return runner
    }

    private fun runThreeSequentialScenarioRunner(
        runnerName: String = "SequentialScenarioRunnerParity_${System.nanoTime()}",
        numberReplications: Int = FAST_REPS,
        lengthOfReplication: Double = FAST_LENGTH,
        lengthOfReplicationWarmUp: Double = FAST_WARMUP
    ): ScenarioRunner {
        val runner = ScenarioRunner(
            runnerName,
            buildThreeQueueScenarios(
                numberReplications = numberReplications,
                lengthOfReplication = lengthOfReplication,
                lengthOfReplicationWarmUp = lengthOfReplicationWarmUp
            )
        )
        runner.simulate()
        return runner
    }

    private fun failureRunParameters(scenarioName: String) =
        Model("FailureScenarioRunParameters", autoCSVReports = false)
            .extractRunParameters()
            .copy(
                experimentName = scenarioName,
                numberOfReplications = FAST_REPS,
                lengthOfReplication = FAST_LENGTH,
                lengthOfReplicationWarmUp = FAST_WARMUP
            )

    private fun buildFailingScenario(
        scenarioName: String = "FailingScenario"
    ): Scenario {
        val builder = object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model {
                throw RuntimeException(INTENTIONAL_FAILURE_MESSAGE)
            }
        }

        return Scenario(
            modelBuilder = builder,
            name = scenarioName,
            runParameters = failureRunParameters(scenarioName)
        )
    }

    private fun runRunnerWithFailingScenario(
        runnerName: String = "ConcurrentScenarioRunnerFailure_${System.nanoTime()}"
    ): ConcurrentScenarioRunner {
        val runner = ConcurrentScenarioRunner(
            runnerName,
            listOf(
                buildQueueScenario(
                    scenarioName = "OneServer",
                    modelName = "CSR_Failure_1S",
                    numServers = 1,
                    arrivalStream = STREAM_S1_ARRIVE,
                    serviceStream = STREAM_S1_SERVE
                ),
                buildFailingScenario(),
                buildQueueScenario(
                    scenarioName = "TwoServers",
                    modelName = "CSR_Failure_2S",
                    numServers = 2,
                    arrivalStream = STREAM_S2_ARRIVE,
                    serviceStream = STREAM_S2_SERVE
                )
            )
        )
        runBlocking { runner.simulate() }
        return runner
    }

    private fun assertAllScenariosSucceeded(runner: ConcurrentScenarioRunner) {
        val failures = runner.scenarioList.mapNotNull { scenario ->
            val run = scenario.simulationRun
            when {
                run == null -> "${scenario.name}: simulationRun was null"
                run.hasError -> "${scenario.name}: ${run.runErrorMsg}"
                !run.hasResults -> "${scenario.name}: simulationRun had no results"
                else -> null
            }
        }

        assertTrue(
            failures.isEmpty(),
            "Expected all scenarios to succeed, but found:\n${failures.joinToString("\n")}"
        )
    }

    private fun assertDbExperimentNamesMatchScenarios(runner: ConcurrentScenarioRunner) {
        val expected = runner.scenarioList.map { it.name }.toSet()
        val actual = runner.kslDb.experimentNames.toSet()

        assertEquals(
            expected,
            actual,
            "DB experiment names must match scenario names"
        )
    }

    private fun systemTimeObservations(
        runner: ConcurrentScenarioRunner,
        scenarioName: String
    ): DoubleArray {
        val scenario = runner.scenarioByName(scenarioName)
        assertNotNull(scenario, "Expected scenario '$scenarioName'")

        val run = scenario!!.simulationRun
        assertNotNull(run, "Scenario '$scenarioName' must have a simulationRun")

        val observations = run!!.replicationObservations("System Time")
        assertNotNull(observations, "Scenario '$scenarioName' must have System Time observations")

        return observations!!
    }

    private fun systemTimeAverage(
        runner: ConcurrentScenarioRunner,
        scenarioName: String
    ): Double {
        return systemTimeObservations(runner, scenarioName).average()
    }

    @BeforeAll
    fun runScenarios() {
        runner = runThreeScenarioRunner("ConcurrentScenarioRunnerTest")
    }

    // ── Tier 1: Smoke ─────────────────────────────────────────────────────────

    @Test
    fun concurrentRunnerCompletesAllScenariosAndCommitsAllExperiments() {
        val runner = runThreeScenarioRunner("ConcurrentScenarioRunnerHappyPath_${System.nanoTime()}")

        assertEquals(3, runner.scenarioList.size, "runner must contain three scenarios")
        assertAllScenariosSucceeded(runner)
        assertDbExperimentNamesMatchScenarios(runner)

        val observations = runner.observationsAsMap("System Time")
        val expectedNames = runner.scenarioList.map { it.name }.toSet()

        assertEquals(
            expectedNames,
            observations.keys,
            "observationsAsMap must return System Time data for every scenario"
        )

        for ((name, obs) in observations) {
            assertEquals(
                FAST_REPS,
                obs.size,
                "Scenario '$name' must have $FAST_REPS System Time observations"
            )
            assertTrue(
                obs.all { it.isFinite() && it > 0.0 },
                "Scenario '$name' System Time observations must all be finite and positive"
            )
        }
    }

    @Test
    fun concurrentResultsMatchSequentialScenarioRunnerForSystemTime() {
        val sequentialRunner = runThreeSequentialScenarioRunner(
            "SequentialScenarioRunnerParity_${System.nanoTime()}"
        )
        val concurrentRunner = runThreeScenarioRunner(
            "ConcurrentScenarioRunnerParity_${System.nanoTime()}"
        )

        assertAllScenariosSucceeded(concurrentRunner)
        assertDbExperimentNamesMatchScenarios(concurrentRunner)

        for (scenarioName in listOf("OneServer", "TwoServers", "ThreeServers")) {
            val sequentialScenario = sequentialRunner.scenarioByName(scenarioName)
            assertNotNull(sequentialScenario, "Sequential runner must contain '$scenarioName'")

            val sequentialRun = sequentialScenario!!.simulationRun
            assertNotNull(sequentialRun, "Sequential scenario '$scenarioName' must have a simulationRun")
            assertFalse(sequentialRun!!.hasError, "Sequential scenario '$scenarioName' must not have a run error")
            assertTrue(sequentialRun.hasResults, "Sequential scenario '$scenarioName' must have results")

            val concurrentScenario = concurrentRunner.scenarioByName(scenarioName)
            assertNotNull(concurrentScenario, "Concurrent runner must contain '$scenarioName'")

            val concurrentRun = concurrentScenario!!.simulationRun
            assertNotNull(concurrentRun, "Concurrent scenario '$scenarioName' must have a simulationRun")

            assertEquals(
                sequentialRun.responseNames.toSet(),
                concurrentRun!!.responseNames.toSet(),
                "Response names must match for scenario '$scenarioName'"
            )

            val sequentialObs = sequentialRun.replicationObservations("System Time")
            assertNotNull(sequentialObs, "Sequential scenario '$scenarioName' must have System Time observations")

            val concurrentObs = concurrentRun.replicationObservations("System Time")
            assertNotNull(concurrentObs, "Concurrent scenario '$scenarioName' must have System Time observations")

            assertArrayEquals(
                sequentialObs!!,
                concurrentObs!!,
                1.0e-10,
                "System Time replication observations must match for scenario '$scenarioName'"
            )
        }
    }

    @Test
    fun failingScenarioDoesNotCancelSuccessfulScenarios() {
        val runner = runRunnerWithFailingScenario()

        for (scenarioName in listOf("OneServer", "TwoServers")) {
            val run = runner.scenarioByName(scenarioName)!!.simulationRun
            assertNotNull(run, "Scenario '$scenarioName' must have a simulationRun")
            assertFalse(run.hasError, "Scenario '$scenarioName' must not have a run error")
            assertTrue(run.hasResults, "Scenario '$scenarioName' must have results")
        }

        val failedRun = runner.scenarioByName("FailingScenario")!!.simulationRun
        assertNotNull(failedRun, "Failing scenario must have an explicit simulationRun")
        assertTrue(failedRun.hasError, "Failing scenario must record the run error")
        assertFalse(failedRun.hasResults, "Failing scenario must not have results")
        assertTrue(
            failedRun.runErrorMsg.contains(INTENTIONAL_FAILURE_MESSAGE),
            "Failing scenario runErrorMsg must include the original exception message"
        )
    }

    @Test
    fun failedScenarioIsNotCommittedToDatabase() {
        val runner = runRunnerWithFailingScenario(
            "ConcurrentScenarioRunnerFailureDb_${System.nanoTime()}"
        )

        assertEquals(
            setOf("OneServer", "TwoServers"),
            runner.kslDb.experimentNames.toSet(),
            "Only successful scenarios should be committed to the database"
        )
    }

    @Test
    fun observationsAsMapOmitsFailedScenario() {
        val runner = runRunnerWithFailingScenario(
            "ConcurrentScenarioRunnerFailureObservations_${System.nanoTime()}"
        )

        assertEquals(
            setOf("OneServer", "TwoServers"),
            runner.observationsAsMap("System Time").keys,
            "observationsAsMap should include only scenarios with successful observations"
        )
    }

    @RepeatedTest(5)
    fun concurrentRunnerIsStableAcrossRepeatedRuns() {
        val runner = runThreeScenarioRunner("ConcurrentScenarioRunnerStress_${System.nanoTime()}")

        assertAllScenariosSucceeded(runner)
        assertDbExperimentNamesMatchScenarios(runner)

        val observations = runner.observationsAsMap("System Time")
        assertEquals(
            runner.scenarioList.map { it.name }.toSet(),
            observations.keys,
            "System Time observations must be present for every scenario"
        )
    }

    @Tag("slow")
    @RepeatedTest(50)
    fun concurrentRunnerIsStableAcrossManyRepeatedRuns() {
        val runner = runThreeScenarioRunner("ConcurrentScenarioRunnerSlowStress_${System.nanoTime()}")

        assertAllScenariosSucceeded(runner)
        assertDbExperimentNamesMatchScenarios(runner)

        val observations = runner.observationsAsMap("System Time")
        assertEquals(
            runner.scenarioList.map { it.name }.toSet(),
            observations.keys,
            "System Time observations must be present for every scenario"
        )
    }

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
