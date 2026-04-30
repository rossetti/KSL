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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for [ConcurrentScenarioRunner].
 *
 * Three M/M/c scenarios (c = 1, 2, 3) are executed concurrently.  Each scenario uses a
 * dedicated [ModelBuilderIfc] that constructs a completely fresh [Model] on every build()
 * call.  The primary scenarios use distinct stream numbers to keep their queueing behavior
 * visibly different.  The CRN-focused tests below intentionally reuse the same stream numbers
 * across fresh models to verify that each model's internal stream provider isolates stream state.
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

        private const val CRN_ARRIVE_STREAM = 31
        private const val CRN_SERVICE_STREAM = 32
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

    private fun buildCommonRandomNumberQueueScenarios(
        numberReplications: Int = FAST_REPS,
        lengthOfReplication: Double = FAST_LENGTH,
        lengthOfReplicationWarmUp: Double = FAST_WARMUP
    ): List<Scenario> {
        return listOf(
            "CRN_A" to "CSR_CRN_A",
            "CRN_B" to "CSR_CRN_B",
            "CRN_C" to "CSR_CRN_C"
        ).map { (scenarioName, modelName) ->
            buildQueueScenario(
                scenarioName = scenarioName,
                modelName = modelName,
                numServers = 1,
                arrivalStream = CRN_ARRIVE_STREAM,
                serviceStream = CRN_SERVICE_STREAM,
                numberReplications = numberReplications,
                lengthOfReplication = lengthOfReplication,
                lengthOfReplicationWarmUp = lengthOfReplicationWarmUp
            )
        }
    }

    private fun runCommonRandomNumberConcurrentRunner(
        runnerName: String = "ConcurrentScenarioRunnerCRN_${System.nanoTime()}"
    ): ConcurrentScenarioRunner {
        val runner = ConcurrentScenarioRunner(
            runnerName,
            buildCommonRandomNumberQueueScenarios()
        )
        runBlocking { runner.simulate() }
        return runner
    }

    private fun runCommonRandomNumberSequentialRunner(
        runnerName: String = "SequentialScenarioRunnerCRN_${System.nanoTime()}"
    ): ScenarioRunner {
        val runner = ScenarioRunner(
            runnerName,
            buildCommonRandomNumberQueueScenarios()
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

    private fun dbSystemTimeRows(
        runner: ConcurrentScenarioRunner,
        scenarioName: String
    ) = runner.kslDb.withinRepViewData()
        .filter { it.exp_name == scenarioName && it.stat_name == "System Time" }
        .sortedBy { it.rep_id }

    private fun dbSystemTimeObservations(
        runner: ConcurrentScenarioRunner,
        scenarioName: String
    ): DoubleArray {
        return dbSystemTimeRows(runner, scenarioName)
            .map { it.rep_value ?: Double.NaN }
            .toDoubleArray()
    }

    private fun assertDbExperimentMetadataMatchesScenario(
        runner: ConcurrentScenarioRunner,
        scenarioName: String
    ) {
        val scenario = runner.scenarioByName(scenarioName)
        assertNotNull(scenario, "Expected scenario '$scenarioName'")

        val experiment = runner.kslDb.fetchExperimentData(scenarioName)
        assertNotNull(experiment, "DB must contain experiment metadata for '$scenarioName'")

        assertEquals(scenarioName, experiment!!.exp_name)
        assertEquals(
            scenario!!.lengthOfReplication,
            experiment.length_of_rep ?: Double.NaN,
            1.0e-10,
            "DB replication length must match scenario '$scenarioName'"
        )
        assertEquals(
            scenario.lengthOfReplicationWarmUp,
            experiment.length_of_warm_up ?: Double.NaN,
            1.0e-10,
            "DB warm-up length must match scenario '$scenarioName'"
        )
        assertEquals(
            scenario.scenarioRunParameters.replicationInitializationOption,
            experiment.rep_init_option,
            "DB replication initialization option must match scenario '$scenarioName'"
        )
        assertEquals(
            scenario.scenarioRunParameters.resetStartStreamOption,
            experiment.reset_start_stream_option,
            "DB reset-start-stream option must match scenario '$scenarioName'"
        )
        assertEquals(
            scenario.scenarioRunParameters.advanceNextSubStreamOption,
            experiment.adv_next_sub_stream_option,
            "DB advance-next-substream option must match scenario '$scenarioName'"
        )
        assertEquals(
            scenario.scenarioRunParameters.antitheticOption,
            experiment.antithetic_option,
            "DB antithetic option must match scenario '$scenarioName'"
        )
        assertEquals(
            scenario.scenarioRunParameters.numberOfStreamAdvancesPriorToRunning,
            experiment.num_stream_advances,
            "DB stream-advance count must match scenario '$scenarioName'"
        )
        assertEquals(
            scenario.scenarioRunParameters.garbageCollectAfterReplicationFlag,
            experiment.gc_after_rep_option,
            "DB garbage-collect-after-replication option must match scenario '$scenarioName'"
        )
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
    fun concurrentIdenticalScenariosCanUseCommonRandomNumberStreams() {
        val runner = runCommonRandomNumberConcurrentRunner()

        assertAllScenariosSucceeded(runner)
        assertDbExperimentNamesMatchScenarios(runner)

        val observations = runner.observationsAsMap("System Time")
        val scenarioNames = setOf("CRN_A", "CRN_B", "CRN_C")

        assertEquals(
            scenarioNames,
            observations.keys,
            "All CRN scenarios must produce System Time observations"
        )

        val baseline = observations.getValue("CRN_A")

        for (scenarioName in listOf("CRN_B", "CRN_C")) {
            assertArrayEquals(
                baseline,
                observations.getValue(scenarioName),
                1.0e-10,
                "Identical concurrent scenarios using the same stream numbers must produce identical observations"
            )
        }

        for ((scenarioName, obs) in observations) {
            assertArrayEquals(
                obs,
                dbSystemTimeObservations(runner, scenarioName),
                1.0e-10,
                "DB System Time rows must preserve CRN observations for '$scenarioName'"
            )
        }
    }

    @Test
    fun concurrentCommonRandomNumberResultsMatchSequentialExecution() {
        val sequentialRunner = runCommonRandomNumberSequentialRunner()
        val concurrentRunner = runCommonRandomNumberConcurrentRunner()

        assertAllScenariosSucceeded(concurrentRunner)
        assertDbExperimentNamesMatchScenarios(concurrentRunner)

        for (scenarioName in listOf("CRN_A", "CRN_B", "CRN_C")) {
            val sequentialRun = sequentialRunner.scenarioByName(scenarioName)!!.simulationRun
            assertNotNull(sequentialRun, "Sequential scenario '$scenarioName' must have a simulationRun")

            val concurrentRun = concurrentRunner.scenarioByName(scenarioName)!!.simulationRun
            assertNotNull(concurrentRun, "Concurrent scenario '$scenarioName' must have a simulationRun")

            assertArrayEquals(
                sequentialRun!!.replicationObservations("System Time")!!,
                concurrentRun!!.replicationObservations("System Time")!!,
                1.0e-10,
                "Common-random-number observations must match sequential execution for '$scenarioName'"
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
    fun dbExperimentRecordsMatchScenarioRunParameters() {
        val runner = runThreeScenarioRunner("ConcurrentScenarioRunnerDbMetadata_${System.nanoTime()}")

        assertAllScenariosSucceeded(runner)
        assertDbExperimentNamesMatchScenarios(runner)

        for (scenarioName in listOf("OneServer", "TwoServers", "ThreeServers")) {
            assertDbExperimentMetadataMatchesScenario(runner, scenarioName)
        }
    }

    @Test
    fun dbSystemTimeRowsMatchSimulationRunResults() {
        val runner = runThreeScenarioRunner("ConcurrentScenarioRunnerDbRows_${System.nanoTime()}")

        assertAllScenariosSucceeded(runner)
        assertDbExperimentNamesMatchScenarios(runner)

        for (scenarioName in listOf("OneServer", "TwoServers", "ThreeServers")) {
            assertArrayEquals(
                systemTimeObservations(runner, scenarioName),
                dbSystemTimeObservations(runner, scenarioName),
                1.0e-10,
                "DB System Time rows must match SimulationRun observations for '$scenarioName'"
            )
        }
    }

    @Test
    fun dbWithinRepRowsExistForEverySuccessfulReplicationOnly() {
        val runner = runRunnerWithFailingScenario(
            "ConcurrentScenarioRunnerDbIntegrityFailure_${System.nanoTime()}"
        )

        val expectedSuccessfulNames = setOf("OneServer", "TwoServers")
        val actualNames = runner.kslDb.withinRepViewData()
            .filter { it.stat_name == "System Time" }
            .map { it.exp_name }
            .toSet()

        assertEquals(
            expectedSuccessfulNames,
            actualNames,
            "DB System Time rows should exist only for successful scenarios"
        )

        for (scenarioName in expectedSuccessfulNames) {
            val rows = dbSystemTimeRows(runner, scenarioName)

            assertEquals(
                FAST_REPS,
                rows.size,
                "DB must contain one System Time row per replication for '$scenarioName'"
            )
            assertEquals(
                (1..FAST_REPS).toList(),
                rows.map { it.rep_id },
                "DB replication ids must be contiguous for '$scenarioName'"
            )
            assertTrue(
                rows.all { it.rep_value != null && it.rep_value!!.isFinite() && it.rep_value!! > 0.0 },
                "DB System Time values must be finite and positive for '$scenarioName'"
            )
        }

        assertTrue(
            dbSystemTimeRows(runner, "FailingScenario").isEmpty(),
            "Failed scenario must not have System Time rows in the DB"
        )
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

    // ── Tier 4: Runner Options and Edge Cases ────────────────────────────────

    @Test
    fun duplicateScenarioNamesAreRejected() {
        val duplicateName = "DuplicateScenario"

        val exception = assertFailsWith<IllegalArgumentException> {
            ConcurrentScenarioRunner(
                "ConcurrentScenarioRunnerDuplicateNames_${System.nanoTime()}",
                listOf(
                    buildQueueScenario(
                        scenarioName = duplicateName,
                        modelName = "CSR_Duplicate_1",
                        numServers = 1,
                        arrivalStream = STREAM_S1_ARRIVE,
                        serviceStream = STREAM_S1_SERVE
                    ),
                    buildQueueScenario(
                        scenarioName = duplicateName,
                        modelName = "CSR_Duplicate_2",
                        numServers = 2,
                        arrivalStream = STREAM_S2_ARRIVE,
                        serviceStream = STREAM_S2_SERVE
                    )
                )
            )
        }

        assertTrue(
            exception.message!!.contains(duplicateName),
            "Duplicate-name error should mention the scenario name"
        )
    }

    @Test
    fun addScenarioRejectsDuplicateScenarioName() {
        val runner = ConcurrentScenarioRunner(
            "ConcurrentScenarioRunnerDuplicateAdd_${System.nanoTime()}",
            listOf(
                buildQueueScenario(
                    scenarioName = "DuplicateAdd",
                    modelName = "CSR_DuplicateAdd_1",
                    numServers = 1,
                    arrivalStream = STREAM_S1_ARRIVE,
                    serviceStream = STREAM_S1_SERVE
                )
            )
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            runner.addScenario(
                buildQueueScenario(
                    scenarioName = "DuplicateAdd",
                    modelName = "CSR_DuplicateAdd_2",
                    numServers = 2,
                    arrivalStream = STREAM_S2_ARRIVE,
                    serviceStream = STREAM_S2_SERVE
                )
            )
        }

        assertTrue(
            exception.message!!.contains("DuplicateAdd"),
            "Duplicate-name error should mention the scenario name"
        )
    }

    @Test
    fun numReplicationsPerScenarioUpdatesAllScenariosBeforeSimulation() {
        val runner = buildThreeScenarioRunner(
            "ConcurrentScenarioRunnerNumReps_${System.nanoTime()}"
        )

        runner.numReplicationsPerScenario(3)
        runBlocking { runner.simulate() }

        assertAllScenariosSucceeded(runner)

        for (scenario in runner.scenarioList) {
            assertEquals(
                3,
                scenario.simulationRun!!.numberOfReplications,
                "Scenario '${scenario.name}' must use the overridden replication count"
            )
            assertEquals(
                3,
                dbSystemTimeRows(runner, scenario.name).size,
                "DB must contain one System Time row per overridden replication"
            )
        }
    }

    @Test
    fun numReplicationsPerScenarioRejectsInvalidValues() {
        val runner = buildThreeScenarioRunner(
            "ConcurrentScenarioRunnerInvalidNumReps_${System.nanoTime()}"
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            runner.numReplicationsPerScenario(0)
        }

        assertTrue(
            exception.message!!.contains(">= 1"),
            "Invalid replication-count error should explain the lower bound"
        )
    }

    @Test
    fun simulateRunsOnlyValidSelectedScenarioIndices() {
        val runner = buildThreeScenarioRunner(
            "ConcurrentScenarioRunnerSelectedIndices_${System.nanoTime()}"
        )

        runBlocking { runner.simulate(scenarios = -1..1) }

        assertNotNull(runner.scenarioByName("OneServer")!!.simulationRun)
        assertNotNull(runner.scenarioByName("TwoServers")!!.simulationRun)
        assertNull(
            runner.scenarioByName("ThreeServers")!!.simulationRun,
            "Unselected scenario must not be simulated"
        )

        assertEquals(
            setOf("OneServer", "TwoServers"),
            runner.kslDb.experimentNames.toSet(),
            "DB must contain only selected valid scenario indices"
        )
    }

    @Test
    fun simulateWithClearAllDataFalsePreservesExistingDbRowsForNewScenario() {
        val runner = buildThreeScenarioRunner(
            "ConcurrentScenarioRunnerAppendDb_${System.nanoTime()}"
        )

        runBlocking { runner.simulate(scenarios = 0..0) }

        assertEquals(
            setOf("OneServer"),
            runner.kslDb.experimentNames.toSet()
        )

        runBlocking { runner.simulate(scenarios = 1..1, clearAllData = false) }

        assertEquals(
            setOf("OneServer", "TwoServers"),
            runner.kslDb.experimentNames.toSet(),
            "clearAllData=false should preserve existing DB rows when names do not collide"
        )
    }

    @Test
    fun simulateWithDefaultClearAllDataReplacesPreviousDbRows() {
        val runner = buildThreeScenarioRunner(
            "ConcurrentScenarioRunnerClearDb_${System.nanoTime()}"
        )

        runBlocking { runner.simulate(scenarios = 0..0) }
        runBlocking { runner.simulate(scenarios = 1..1) }

        assertEquals(
            setOf("TwoServers"),
            runner.kslDb.experimentNames.toSet(),
            "Default clearAllData=true should clear previous DB rows before committing selected results"
        )
        assertTrue(
            dbSystemTimeRows(runner, "OneServer").isEmpty(),
            "Previous DB rows for OneServer should be cleared"
        )
        assertEquals(
            FAST_REPS,
            dbSystemTimeRows(runner, "TwoServers").size,
            "Selected scenario should have committed DB rows"
        )
    }

    @Test
    fun emptyRunnerSimulationCompletesWithoutResults() {
        val runner = ConcurrentScenarioRunner(
            "ConcurrentScenarioRunnerEmpty_${System.nanoTime()}"
        )

        runBlocking { runner.simulate() }

        assertTrue(runner.scenarioList.isEmpty())
        assertTrue(runner.kslDb.experimentNames.isEmpty())
        assertTrue(runner.observationsAsMap("System Time").isEmpty())
    }
}
