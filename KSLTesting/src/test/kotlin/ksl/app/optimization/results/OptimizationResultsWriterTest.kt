package ksl.app.optimization.results

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.app.KSLAppSession
import ksl.app.RunSpec
import ksl.app.config.ModelReference
import ksl.app.config.ModelRunTemplate
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.app.config.optimization.OptimizationProblemSpec
import ksl.app.config.optimization.OptimizationRunConfiguration
import ksl.app.config.optimization.SolverSpec
import ksl.app.session.RunResult
import ksl.examples.general.models.LKInventoryModel
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Substrate-level tests for the optimization results writers
 *  (`ksl.app.optimization.results.*`).
 *
 *  All tests use the substrate's entry point — `KSLAppSession.submit(
 *  RunSpec.Optimization(...))` — to produce a real
 *  `RunResult.OptimizationCompleted` fixture, then exercise each
 *  writer against that fixture.  No Swing dependency: any future
 *  non-Swing UI shell would consume these writers identically.
 *
 *  Controller-integration tests (which exercise SimoptAppController's
 *  state machinery) stay in KSLAppSwingSimopt.
 */
class OptimizationResultsWriterTest {

    private companion object {
        const val LK_ID = "LKInventoryModel"
        const val TIMEOUT_MS = 60_000L
    }

    private val lkBuilder = object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model {
            val model = Model(LK_ID, autoCSVReports = false)
            LKInventoryModel(model, "Inventory")
            model.lengthOfReplication = 120.0
            model.numberOfReplications = 5
            model.lengthOfReplicationWarmUp = 20.0
            return model
        }
    }

    private val lkProvider = MapModelProvider(LK_ID, lkBuilder)

    /** Build a tiny but real optimization configuration — 3 SHC
     *  iterations × 3 reps against a single decision variable.
     *  Runs in well under a second on a developer laptop. */
    private fun lkConfig(
        analysisName: String = "WriterTestAnalysis"
    ): OptimizationRunConfiguration {
        val model = lkProvider.provideModel(LK_ID)
        return OptimizationRunConfiguration(
            output = ksl.app.config.optimization.OptimizationOutputConfig(
                analysisName = analysisName
            ),
            model = ModelRunTemplate(
                modelReference = ModelReference.ByProviderId(LK_ID),
                runParameters  = model.extractRunParameters()
            ),
            problem = OptimizationProblemSpec(
                problemName = "InventoryProblem",
                modelIdentifier = LK_ID,
                objectiveResponseName = "TotalCost",
                inputs = listOf(
                    OptimizationInputSpec(
                        "Inventory.orderQuantity",
                        lowerBound = 1.0, upperBound = 10.0, granularity = 1.0
                    ),
                    OptimizationInputSpec(
                        "Inventory.reorderPoint",
                        lowerBound = 1.0, upperBound = 10.0, granularity = 1.0
                    )
                )
            ),
            solver = SolverSpec.StochasticHillClimbing(
                maxIterations = 2,
                replicationsPerEvaluation = 1,
                name = "shc-test"
            )
        )
    }

    /** Submit the supplied config and block until completion,
     *  returning the typed `OptimizationCompleted` result.  Fails
     *  the test with a clear message on cancel / failure / timeout. */
    private fun runAndAwaitCompletion(
        config: OptimizationRunConfiguration = lkConfig()
    ): RunResult.OptimizationCompleted {
        return runBlocking {
            KSLAppSession(lkProvider, this).use { session ->
                val handle = session.submit(RunSpec.Optimization(config))
                val result = withTimeout(TIMEOUT_MS) { handle.result.await() }
                assertIs<RunResult.OptimizationCompleted>(
                    result,
                    "Expected OptimizationCompleted; got ${result::class.simpleName}"
                )
                result
            }
        }
    }

    // ── IterationHistoryCsvWriter ─────────────────────────────────

    @Test
    fun `iteration history CSV header lists fixed columns then inputs in declaration order`() {
        val config = lkConfig()
        val result = runAndAwaitCompletion(config)
        val problem = config.problem!!
        val csv = IterationHistoryCsvWriter.encode(result.iterationHistory, problem)
        val cols = csv.lineSequence().first().split(",")
        assertEquals("iteration", cols[0])
        assertEquals("oracle_calls", cols[1])
        assertEquals("replications", cols[2])
        assertEquals("est_obj", cols[3])
        assertEquals("pen_obj", cols[4])
        for ((i, spec) in problem.inputs.withIndex()) {
            assertEquals(spec.name, cols[5 + i],
                "Input column ${spec.name} must appear in declaration order")
        }
    }

    @Test
    fun `iteration history CSV row count matches snapshot history length`() {
        val config = lkConfig()
        val result = runAndAwaitCompletion(config)
        val csv = IterationHistoryCsvWriter.encode(result.iterationHistory, config.problem!!)
        val rowCount = csv.lineSequence().filter { it.isNotBlank() }.count()
        assertEquals(1 + result.iterationHistory.size, rowCount)
    }

    // ── BestSolutionCsvWriter ─────────────────────────────────────

    @Test
    fun `best solution CSV has one data row with inputs and objectives`() {
        val config = lkConfig()
        val result = runAndAwaitCompletion(config)
        val csv = BestSolutionCsvWriter.encode(result.bestSolution, config.problem!!)
        val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(2, lines.size, "best_solution.csv = header + one row")
        val header = lines[0].split(",")
        assertEquals("pen_obj", header.last())
        assertEquals("est_obj", header[header.size - 2])
        assertEquals(config.problem!!.inputs.size + 2, header.size)
    }

    // ── RunSummaryWriter (forCompleted / encode / decode) ────────

    @Test
    fun `run summary TOML carries every required key and round-trip-safe types`() {
        val config = lkConfig()
        val result = runAndAwaitCompletion(config)
        val summary = RunSummaryWriter.forCompleted(config, result)
        assertEquals(ResultsStatus.COMPLETED, summary.status)
        assertEquals(config.output.analysisName, summary.analysisName)
        assertEquals(result.summary.runId, summary.runId)
        assertEquals(result.summary.completedItems, summary.totalIterations)
        assertNotNull(summary.bestEstimatedObjective)
        assertNotNull(summary.bestFoundAtIteration)
        assertTrue(summary.bestInputs.isNotEmpty())
        val toml = RunSummaryWriter.encode(summary)
        assertContains(toml, "status = \"COMPLETED\"")
        assertContains(toml, "[bestInputs]")
        assertContains(toml, "elapsedMillis = ")
    }

    @Test
    fun `summary toml round-trips through encode then decode preserving every field`() {
        val config = lkConfig()
        val result = runAndAwaitCompletion(config)
        val summary = RunSummaryWriter.forCompleted(config, result)
        val tomlText = RunSummaryWriter.encode(summary)
        val decoded = RunSummaryWriter.decode(tomlText)
        assertEquals(summary, decoded)
    }

    @Test
    fun `summary toml key order leads with human-friendly fields and ends with runId before tables`(@TempDir tempDir: Path) {
        val config = lkConfig()
        val result = runAndAwaitCompletion(config)
        val runDir = tempDir.resolve("run-001")
        val summary = RunSummaryWriter.forCompleted(config, result, runDir = runDir)
        val toml = RunSummaryWriter.encode(summary)
        // Match on `<key> = ` (key + space + equals + space) so we
        // skip occurrences inside @TomlComment blocks.  Field
        // declaration order on `RunSummary` is the contract; tomlkt
        // preserves it.
        val iAnalysis = toml.indexOf("analysisName = ")
        val iRunDir = toml.indexOf("runDirectory = ")
        val iAlgorithm = toml.indexOf("algorithm = ")
        val iRunId = toml.indexOf("runId = ")
        val iBestInputs = toml.indexOf("[bestInputs]")
        assertTrue(iAnalysis in 0 until iRunDir,
            "analysisName must come before runDirectory")
        assertTrue(iRunDir in 0 until iAlgorithm,
            "runDirectory must come before algorithm")
        assertTrue(iAlgorithm in 0 until iRunId,
            "algorithm must come before runId")
        assertTrue(iRunId in 0 until iBestInputs,
            "runId (substrate cross-reference) must precede [bestInputs]")
    }

    // ── RunSummaryWriter.forIncomplete ────────────────────────────

    @Test
    fun `run summary TOML for incomplete omits objective fields when no iteration fired`() {
        val config = lkConfig("PartialRun")
        val summary = RunSummaryWriter.forIncomplete(
            config = config,
            status = ResultsStatus.CANCELLED,
            runId = "rr-test",
            startTimeIso = "2026-05-27T00:00:00Z",
            endTimeIso = "2026-05-27T00:00:01Z",
            elapsedMillis = 1_000,
            latestBest = null,
            statusReason = "Cancelled by user"
        )
        assertEquals(ResultsStatus.CANCELLED, summary.status)
        assertEquals(0, summary.totalIterations)
        assertNull(summary.bestEstimatedObjective)
        assertNull(summary.bestFoundAtIteration)
        val toml = RunSummaryWriter.encode(summary)
        assertContains(toml, "status = \"CANCELLED\"")
        assertContains(toml, "statusReason = \"Cancelled by user\"")
        assertFalse("bestEstimatedObjective" in toml,
            "Cancelled run with no iteration must not emit a best-estimated-objective key")
    }

    @Test
    fun `run summary TOML for incomplete carries latest best when iteration fired`() {
        val config = lkConfig("PartialRunWithBest")
        val latest = LatestBestSnapshot(
            iteration = 5,
            estimatedObjective = 87.5,
            bestInputs = mapOf("x" to 3.0, "y" to 7.5)
        )
        val summary = RunSummaryWriter.forIncomplete(
            config = config,
            status = ResultsStatus.CANCELLED,
            runId = "rr-partial",
            startTimeIso = "2026-05-27T00:00:00Z",
            endTimeIso = "2026-05-27T00:00:10Z",
            elapsedMillis = 10_000,
            latestBest = latest,
            statusReason = "Cancelled by user"
        )
        assertEquals(5, summary.totalIterations)
        assertEquals(87.5, summary.bestEstimatedObjective)
        assertEquals(5, summary.bestFoundAtIteration)
        assertEquals(2, summary.bestInputs.size)
        val toml = RunSummaryWriter.encode(summary)
        assertContains(toml, "bestEstimatedObjective = 87.5")
        assertContains(toml, "[bestInputs]")
        assertContains(toml, "x = 3.0")
        assertContains(toml, "y = 7.5")
    }

    // ── TOML safety — dotted keys ────────────────────────────────

    @Test
    fun `toml safely quotes dotted keys used by RandomRestartSolver flattening`() {
        val config = lkConfig("DottedKeys")
        // Hand-craft a summary with the dotted-key shape
        // `RandomRestartSolver.configurationProperties` produces:
        // each inner-solver key prefixed with `innerSolver.`.
        val summary = RunSummaryWriter.forIncomplete(
            config = config,
            status = ResultsStatus.CANCELLED,
            runId = "dotted-test",
            startTimeIso = "2026-05-27T00:00:00Z",
            endTimeIso = "2026-05-27T00:00:01Z",
            elapsedMillis = 1_000,
            latestBest = null,
            statusReason = "Test"
        ).copy(
            solverConfiguration = linkedMapOf(
                "clearCacheBetweenRuns" to "true",
                "innerSolver.streamNumber" to "1",
                "innerSolver.maximumNumberIterations" to "100"
            )
        )
        val toml = RunSummaryWriter.encode(summary)
        // Dotted keys must be quoted per TOML 1.0 bare-key rules.
        assertContains(toml, "\"innerSolver.streamNumber\" = \"1\"")
        assertContains(toml, "\"innerSolver.maximumNumberIterations\" = \"100\"")
        // Bare keys stay bare.
        assertContains(toml, "clearCacheBetweenRuns = \"true\"")
    }

    // ── HtmlReportWriter ──────────────────────────────────────────

    @Test
    fun `HTML report contains substrate solver result tables and iteration metrics`(@TempDir tempDir: Path) {
        val config = lkConfig()
        // Need a live Solver instance for the report — submit through
        // the orchestrator directly so we can keep a reference.
        // KSLAppSession rebuilds the solver internally, hiding it
        // from us; tests that need the live Solver capture it before
        // termination via the same Solver.solverResult / instance
        // capture the controller does.  For this substrate-level
        // test we re-build the solver explicitly via the factory.
        val solver = ksl.app.config.optimization.OptimizationSolverFactory(lkProvider)
            .build(config)
        val orchestrator = ksl.app.orchestrator.OptimizationOrchestrator()
        val result = runBlocking {
            val handle = orchestrator.submit(solver = solver)
            val r = withTimeout(TIMEOUT_MS) { handle.result.await() }
            assertIs<RunResult.OptimizationCompleted>(r)
            r
        }
        val solverResult = solver.solverResult
        val reportPath = tempDir.resolve("report.html")
        val ok = HtmlReportWriter.write(
            config = config,
            runResult = result,
            solverResult = solverResult,
            solverInstance = solver,
            path = reportPath
        )
        assertTrue(ok, "HTML report write must succeed")
        val html = Files.readString(reportPath)
        // Substrate-rendered sections from the framework's
        // `solverResult(...)` DSL extension.
        assertContains(html, "Run Summary")
        assertContains(html, "Evaluator Metrics")
        assertContains(html, "Best Solution Found")
        assertContains(html, "Decision Variables")
        assertContains(html, "Objective Function")
        // The writer's own additions.
        assertContains(html, "Solver configuration")
        assertContains(html, "Iteration metrics")
    }

    // ── ConvergencePlotBuilder ────────────────────────────────────

    @Test
    fun `convergence plot builder returns null on empty history`() {
        assertNull(ConvergencePlotBuilder.buildPlot(emptyList()))
    }

    // ── ResultsArtifactWriter (end-to-end coordinator) ───────────

    @Test
    fun `coordinator writes the full artifact set into runDir`(@TempDir tempDir: Path) {
        val config = lkConfig()
        val solver = ksl.app.config.optimization.OptimizationSolverFactory(lkProvider)
            .build(config)
        val orchestrator = ksl.app.orchestrator.OptimizationOrchestrator()
        val result = runBlocking {
            val handle = orchestrator.submit(solver = solver)
            val r = withTimeout(TIMEOUT_MS) { handle.result.await() }
            assertIs<RunResult.OptimizationCompleted>(r)
            r
        }
        val runDir = tempDir.resolve("run-001")
        val artifacts = ResultsArtifactWriter.writeCompleted(
            config = config,
            result = result,
            solverResult = solver.solverResult,
            solverInstance = solver,
            runDir = runDir
        )
        // Text-format artifacts must land regardless of native
        // renderer availability for the PNG.
        assertTrue(artifacts.summaryTomlWritten)
        assertTrue(artifacts.iterationHistoryCsvWritten)
        assertTrue(artifacts.bestSolutionCsvWritten)
        assertTrue(artifacts.reportHtmlWritten)
        assertTrue(Files.exists(runDir.resolve(ArtifactNames.SUMMARY_TOML)))
        assertTrue(Files.exists(runDir.resolve(ArtifactNames.ITERATION_HISTORY_CSV)))
        assertTrue(Files.exists(runDir.resolve(ArtifactNames.BEST_SOLUTION_CSV)))
        assertTrue(Files.exists(runDir.resolve(ArtifactNames.REPORT_HTML)))
    }
}
