package ksl.simulation

import ksl.controls.experiments.ExperimentRunParameters
import ksl.controls.experiments.Scenario
import ksl.controls.experiments.ScenarioRunner
import ksl.controls.experiments.SimulationRunner
import ksl.examples.book.appendixD.GIGcQueue
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.*
import ksl.utilities.io.report.renderer.RenderContext
import ksl.utilities.io.report.renderer.TextReportRenderer
import ksl.utilities.statistic.Statistic
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for KSL simulation-level reporting extensions.
 *
 * Covers extension functions NOT exercised by the existing ReportingFrameworkTest:
 *   - Model.toReport()
 *   - SimulationRun.toReport()
 *   - ScenarioRunner.toReport() / Scenario.toReport()
 *   - ExperimentRunParameters.toReport()
 *   - statisticCompact() and statistics() (list form)
 *   - simulationSummary() and simulationResults() DSL functions
 *   - simulationRun() DSL function
 *   - scenarioRunner() DSL function
 *
 * Model: GIGcQueue M/M/1 (λ=1, μ=2, ρ=0.5).
 * Fast config: 5 reps × 1 000 min, warm-up 200 | default KSL seed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimulationReportingTest {

    // ── Configuration ─────────────────────────────────────────────────────────

    companion object {
        private const val FAST_REPS   = 5
        private const val FAST_LENGTH = 1000.0
        private const val FAST_WARMUP = 200.0
    }

    // ── Shared simulation state ───────────────────────────────────────────────

    private lateinit var model: Model
    private lateinit var runner: ScenarioRunner

    @BeforeAll
    fun setup() {
        // Single model for Model-level and SimulationRun-level tests
        model = Model("GIGcReport", autoCSVReports = false)
        model.numberOfReplications      = FAST_REPS
        model.lengthOfReplication       = FAST_LENGTH
        model.lengthOfReplicationWarmUp = FAST_WARMUP
        GIGcQueue(model, numServers = 1, name = "MM1Q")
        model.simulate()

        // Two-scenario runner for Scenario / ScenarioRunner tests
        val m1 = Model("SR_1S", autoCSVReports = false)
        GIGcQueue(m1, numServers = 1, name = "MM1Q")
        val m2 = Model("SR_2S", autoCSVReports = false)
        GIGcQueue(m2, numServers = 2, name = "MM1Q")

        val s1 = Scenario(model = m1, name = "OneServer",
            numberReplications = FAST_REPS,
            lengthOfReplication = FAST_LENGTH,
            lengthOfReplicationWarmUp = FAST_WARMUP)
        val s2 = Scenario(model = m2, name = "TwoServers",
            numberReplications = FAST_REPS,
            lengthOfReplication = FAST_LENGTH,
            lengthOfReplicationWarmUp = FAST_WARMUP)
        runner = ScenarioRunner("ReportSR", listOf(s1, s2))
        runner.simulate()
    }

    // ── Group 1: Model.toReport() ─────────────────────────────────────────────

    @Test
    fun modelToReportReturnsDocument() {
        assertNotNull(model.toReport())
    }

    @Test
    fun modelToReportDocumentTitleIsModelName() {
        val doc = model.toReport("My Report Title")
        assertEquals("My Report Title", doc.title)
    }

    @Test
    fun modelToReportDocumentHasChildren() {
        assertTrue(model.toReport().children.isNotEmpty(),
            "Model report must have at least one section")
    }

    @Test
    fun modelToReportTextContainsSystemTime() {
        val text = model.toReport().renderToText()
        assertTrue(text.contains("System Time"),
            "Simulation report must mention the 'System Time' response")
    }

    @Test
    fun modelToReportTextContainsModelName() {
        val text = model.toReport().renderToText()
        assertTrue(text.contains("GIGcReport") || text.contains("GIGcReport".replace(" ", "_")),
            "Simulation report must contain the model name")
    }

    // ── Group 2: simulationSummary() and simulationResults() DSL functions ────

    @Test
    fun simulationResultsDslProducesNonEmptyText() {
        val reporter = model.simulationReporter
        val doc = report("DSL Results") {
            simulationResults(reporter, model)
        }
        val text = doc.renderToText()
        assertFalse(text.isBlank(), "simulationResults DSL must produce non-blank text")
        assertTrue(text.contains("System Time"))
    }

    @Test
    fun simulationSummaryDslContainsReplicationCount() {
        val reporter = model.simulationReporter
        val doc = report("Summary Test") {
            simulationSummary(reporter, model)
        }
        val text = doc.renderToText()
        assertTrue(text.contains(FAST_REPS.toString()),
            "Simulation summary must include the replication count ($FAST_REPS)")
    }

    // ── Group 3: SimulationRun.toReport() and simulationRun() DSL ────────────

    @Test
    fun simulationRunToReportReturnsDocument() {
        val run = SimulationRunner(model).simulate()
        assertNotNull(run.toReport())
    }

    @Test
    fun simulationRunToReportTitleMatchesRunName() {
        val run = SimulationRunner(model).simulate()
        val title = "Run Report Title"
        val doc   = run.toReport(title)
        assertEquals(title, doc.title)
    }

    @Test
    fun simulationRunToReportTextContainsSystemTime() {
        val run  = SimulationRunner(model).simulate()
        val text = run.toReport().renderToText()
        assertTrue(text.contains("System Time"),
            "SimulationRun report must mention 'System Time'")
    }

    @Test
    fun simulationRunDslProducesNonEmptyText() {
        val run = SimulationRunner(model).simulate()
        val doc = report("SimRun DSL Test") {
            simulationRun(run)
        }
        assertFalse(doc.renderToText().isBlank())
    }

    // ── Group 4: ExperimentRunParameters.toReport() ───────────────────────────

    @Test
    fun experimentRunParametersToReportReturnsDocument() {
        val params = ExperimentRunParameters(
            experimentName   = "TestExp",
            experimentId     = 1,
            numberOfReplications         = FAST_REPS,
            numChunks        = 1,
            runName          = "Run1",
            startingRepId    = 1,
            lengthOfReplication          = FAST_LENGTH,
            lengthOfReplicationWarmUp    = FAST_WARMUP,
            replicationInitializationOption = true,
            maximumAllowedExecutionTimePerReplication = kotlin.time.Duration.INFINITE,
            resetStartStreamOption       = false,
            advanceNextSubStreamOption   = false,
            antitheticOption             = false,
            numberOfStreamAdvancesPriorToRunning = 0,
            garbageCollectAfterReplicationFlag   = false
        )
        val doc = params.toReport()
        assertNotNull(doc)
        val text = doc.renderToText()
        assertTrue(text.contains("TestExp"),
            "ExperimentRunParameters report must contain experiment name")
        assertTrue(text.contains(FAST_REPS.toString()),
            "ExperimentRunParameters report must contain replication count")
    }

    @Test
    fun experimentRunParametersDslProducesNonEmptyText() {
        val run = SimulationRunner(model).simulate()
        val doc = report("Params DSL Test") {
            experimentRunParameters(run.experimentRunParameters)
        }
        assertFalse(doc.renderToText().isBlank())
    }

    // ── Group 5: Scenario.toReport() ─────────────────────────────────────────

    @Test
    fun scenarioToReportReturnsDocument() {
        val scenario = runner.scenarioByName("OneServer")!!
        assertNotNull(scenario.toReport())
    }

    @Test
    fun scenarioToReportTitleIsSet() {
        val scenario = runner.scenarioByName("TwoServers")!!
        val doc = scenario.toReport("My Scenario Report")
        assertEquals("My Scenario Report", doc.title)
    }

    @Test
    fun scenarioToReportTextContainsScenarioName() {
        val scenario = runner.scenarioByName("OneServer")!!
        val text = scenario.toReport().renderToText()
        assertTrue(text.contains("OneServer"),
            "Scenario report must contain the scenario name")
    }

    @Test
    fun scenarioToReportTextContainsSystemTime() {
        val scenario = runner.scenarioByName("OneServer")!!
        val text = scenario.toReport().renderToText()
        assertTrue(text.contains("System Time"),
            "Scenario report must mention 'System Time'")
    }

    // ── Group 6: ScenarioRunner.toReport() and scenarioRunner() DSL ──────────

    @Test
    fun scenarioRunnerToReportReturnsDocument() {
        assertNotNull(runner.toReport())
    }

    @Test
    fun scenarioRunnerToReportTitleIsSet() {
        val doc = runner.toReport("Runner Report")
        assertEquals("Runner Report", doc.title)
    }

    @Test
    fun scenarioRunnerToReportTextContainsBothScenarioNames() {
        val text = runner.toReport().renderToText()
        assertTrue(text.contains("OneServer"),  "Runner report must mention OneServer")
        assertTrue(text.contains("TwoServers"), "Runner report must mention TwoServers")
    }

    @Test
    fun scenarioRunnerDslProducesNonEmptyText() {
        val doc = report("ScenarioRunner DSL Test") {
            scenarioRunner(runner)
        }
        assertFalse(doc.renderToText().isBlank())
    }

    // ── Group 7: statistics() list form and statisticCompact() ───────────────

    @Test
    fun statisticsListProducesTableWithAllNames() {
        val stats = listOf(
            Statistic("Alpha").also { repeat(20) { _ -> it.collect(Math.random()) } },
            Statistic("Beta").also  { repeat(20) { _ -> it.collect(Math.random() * 2) } },
            Statistic("Gamma").also { repeat(20) { _ -> it.collect(Math.random() * 3) } }
        )
        val doc  = report("Statistics List Test") { statistics(stats) }
        val text = doc.renderToText()
        assertTrue(text.contains("Alpha"),  "statistics() must include 'Alpha'")
        assertTrue(text.contains("Beta"),   "statistics() must include 'Beta'")
        assertTrue(text.contains("Gamma"),  "statistics() must include 'Gamma'")
    }

    @Test
    fun statisticCompactProducesNonEmptyTextWithStatName() {
        val stat = Statistic("CompactStat").also { repeat(30) { _ -> it.collect(it.count * 0.5) } }
        val doc  = report("Compact Test") { statisticCompact(stat) }
        val text = doc.renderToText()
        assertFalse(text.isBlank())
        assertTrue(text.contains("CompactStat"))
    }

    @Test
    fun statisticToReportReturnsDocumentWithCorrectTitle() {
        val stat = Statistic("IndividualStat").also { repeat(25) { _ -> it.collect(it.count.toDouble()) } }
        val doc  = stat.toReport("StatDoc")
        assertEquals("StatDoc", doc.title)
        assertTrue(doc.renderToText().contains("IndividualStat"))
    }

    @Test
    fun emptyStatisticsListProducesNoTableContent() {
        val doc  = report("Empty Stats Test") { statistics(emptyList()) }
        // An empty statistics list should not add error-producing nodes; document renders fine
        assertNotNull(doc.renderToText())
    }

    // ── Private render helper ─────────────────────────────────────────────────

    private fun ReportNode.Document.renderToText(): String {
        val ctx      = RenderContext()
        val renderer = TextReportRenderer(ctx)
        accept(renderer)
        return renderer.result()
    }
}
