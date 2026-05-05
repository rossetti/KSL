package ksl.utilities.io.report

import kotlinx.coroutines.runBlocking
import ksl.app.session.RunRequest
import ksl.app.session.RunResult
import ksl.app.session.Runner
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.Model
import ksl.utilities.io.OutputDirectory
import ksl.utilities.io.dbutil.AcrossRepStatTableData
import ksl.utilities.io.dbutil.FrequencyTableData
import ksl.utilities.io.dbutil.HistogramTableData
import ksl.utilities.io.dbutil.SimulationRunTableData
import ksl.utilities.io.dbutil.SimulationSnapshot
import ksl.utilities.io.dbutil.TimeSeriesResponseTableData
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.extensions.snapshotSimulationResults
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.renderer.RenderContext
import ksl.utilities.io.report.renderer.TextReportRenderer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SimulationSnapshotReportExtensionsTest {

    @Test
    fun completedSnapshotToReportReturnsDocument() {
        val mySnapshot = sampleSnapshot()

        val myDoc = mySnapshot.toReport("Snapshot Report", showPlots = false)

        assertEquals("Snapshot Report", myDoc.title)
        assertTrue(myDoc.children.isNotEmpty(), "Snapshot report must contain sections")
    }

    @Test
    fun renderedTextContainsRunSummaryAndAcrossReplicationStatistics() {
        val myText = sampleSnapshot()
            .toReport("Snapshot Report", showPlots = false, showDiagnostics = true)
            .renderToText()

        assertTrue(myText.contains("Run Summary"))
        assertTrue(myText.contains("Across-Replication Statistics"))
        assertTrue(myText.contains("System Time"))
        assertTrue(myText.contains("Across-Replication Diagnostics"))
    }

    @Test
    fun histogramAndFrequencySectionsRenderFromSnapshotRows() {
        val myText = sampleSnapshot()
            .toReport("Snapshot Report", showPlots = false)
            .renderToText()

        assertTrue(myText.contains("Histograms"))
        assertTrue(myText.contains("Bin Frequencies"))
        assertTrue(myText.contains("Frequencies"))
        assertTrue(myText.contains("Frequency Table"))
    }

    @Test
    fun timeSeriesRowsAggregateByPeriod() {
        val myText = sampleSnapshot()
            .toReport("Snapshot Report", showPlots = false)
            .renderToText()

        assertTrue(myText.contains("Time-Series Responses"))
        assertTrue(myText.contains("Hourly System Time"))
        assertTrue(myText.contains("11.0000"), "Period 1 mean should be rendered")
        assertTrue(myText.contains("15.0000"), "Period 2 mean should be rendered")
    }

    @Test
    fun showPlotsFalseOmitsPlotNodes() {
        val myDoc = sampleSnapshot().toReport("Snapshot Report", showPlots = false)

        assertEquals(0, myDoc.countPlotNodes())
    }

    @Test
    fun emptyOptionalCollectionsRenderWithoutFailure() {
        val mySnapshot = sampleSnapshot().copy(
            acrossRepStats = emptyList(),
            histograms = emptyList(),
            frequencies = emptyList(),
            timeSeries = emptyList()
        )

        val myText = mySnapshot.toReport("Empty Snapshot").renderToText()

        assertFalse(myText.isBlank())
        assertTrue(myText.contains("No across-replication statistics available."))
    }

    @Test
    fun customBlockCanComposeStandardSectionsAndCommentary() {
        val mySnapshot = sampleSnapshot()

        val myDoc = mySnapshot.toReport("Annotated Snapshot") {
            snapshotSimulationResults(
                mySnapshot,
                showPlots = false,
                showDiagnostics = true
            )
            section("Analyst Notes") {
                paragraph("Custom snapshot commentary.")
            }
        }

        val myText = myDoc.renderToText()
        assertTrue(myText.contains("Analyst Notes"))
        assertTrue(myText.contains("Custom snapshot commentary."))
    }

    @Test
    fun completedSnapshotReportWritesToSuppliedOutputDirectoryByDefault() {
        val myOutputDirectory = OutputDirectory(
            Files.createTempDirectory("ksl_snapshot_report_test"),
            "kslOutput.txt"
        )
        val myDoc = sampleSnapshot().toReport(
            title = "Snapshot Output Directory",
            showPlots = false,
            outputDirectory = myOutputDirectory
        )

        val myFile = myDoc.writeMarkdown()

        assertEquals(
            myOutputDirectory.outDir.resolve("Snapshot_Output_Directory.md").toAbsolutePath(),
            myFile.toPath().toAbsolutePath()
        )
        assertTrue(myFile.exists(), "Markdown report file should be written")
    }

    @Test
    fun modelReportWritesToModelOutputDirectoryByDefault() {
        val myModel = Model(
            simulationName = "Model_Report_Output_Test",
            pathToOutputDirectory = Files.createTempDirectory("ksl_model_report_test"),
            autoCSVReports = false
        )
        val myDoc = myModel.toReport("Model Output Directory")

        val myFile = myDoc.writeText()

        assertEquals(
            myModel.outputDirectory.outDir.resolve("Model_Output_Directory.txt").toAbsolutePath(),
            myFile.toPath().toAbsolutePath()
        )
        assertTrue(myFile.exists(), "Text report file should be written")
    }

    @Test
    fun runnerCompletedSnapshotRendersToText() = runBlocking {
        val myModel = Model("SnapshotRunnerTest", autoCSVReports = false)
        myModel.numberOfReplications = 3
        myModel.lengthOfReplication = 500.0
        myModel.lengthOfReplicationWarmUp = 100.0
        GIGcQueue(myModel, numServers = 1, name = "MM1Q")

        val myHandle = Runner().submit(RunRequest.SingleRun(myModel), this)
        val myResult = myHandle.result.await()
        val myCompleted = assertIs<RunResult.Completed>(myResult)

        val myText = myCompleted.snapshot
            .toReport("Runner Snapshot", showPlots = false)
            .renderToText()

        assertNotNull(myCompleted.snapshot.simulationRun.run_end_time_stamp)
        assertTrue(myText.contains("Run Summary"))
        assertTrue(myText.contains("System Time"))
    }

    private fun sampleSnapshot(): SimulationSnapshot.ExperimentCompleted {
        return SimulationSnapshot.ExperimentCompleted(
            simulationRun = SimulationRunTableData(
                run_id = 7,
                exp_id_fk = 3,
                run_name = "Snapshot Test Run",
                num_reps = 2,
                start_rep_id = 1,
                last_rep_id = 2,
                run_start_time_stamp = 1_700_000_000_000,
                run_end_time_stamp = 1_700_000_002_500
            ),
            acrossRepStats = listOf(
                AcrossRepStatTableData(
                    stat_name = "System Time",
                    stat_count = 2.0,
                    average = 12.5,
                    std_dev = 1.5,
                    std_err = 1.0606601718,
                    half_width = 2.2,
                    conf_level = 0.95,
                    minimum = 11.0,
                    maximum = 14.0,
                    sum_of_obs = 25.0,
                    dev_ssq = 2.25,
                    last_value = 14.0,
                    kurtosis = 0.0,
                    skewness = 0.0,
                    lag1_cov = 0.0,
                    lag1_corr = 0.0,
                    von_neumann_lag1_stat = 2.0,
                    num_missing_obs = 0.0
                )
            ),
            histograms = listOf(
                HistogramTableData(
                    response_name = "System Time",
                    bin_label = "[0,10)",
                    bin_num = 1,
                    bin_lower_limit = 0.0,
                    bin_upper_limit = 10.0,
                    bin_count = 4.0,
                    bin_cum_count = 4.0,
                    bin_proportion = 0.4,
                    bin_cum_proportion = 0.4
                ),
                HistogramTableData(
                    response_name = "System Time",
                    bin_label = "[10,20)",
                    bin_num = 2,
                    bin_lower_limit = 10.0,
                    bin_upper_limit = 20.0,
                    bin_count = 6.0,
                    bin_cum_count = 10.0,
                    bin_proportion = 0.6,
                    bin_cum_proportion = 1.0
                )
            ),
            frequencies = listOf(
                FrequencyTableData(
                    name = "Queue Length",
                    cell_label = "0",
                    value = 0,
                    count = 3.0,
                    cum_count = 3.0,
                    proportion = 0.3,
                    cum_proportion = 0.3
                ),
                FrequencyTableData(
                    name = "Queue Length",
                    cell_label = "1",
                    value = 1,
                    count = 7.0,
                    cum_count = 10.0,
                    proportion = 0.7,
                    cum_proportion = 1.0
                )
            ),
            timeSeries = listOf(
                TimeSeriesResponseTableData(
                    rep_id = 1,
                    stat_name = "Hourly System Time",
                    period = 1,
                    start_time = 0.0,
                    end_time = 60.0,
                    length = 60.0,
                    value = 10.0
                ),
                TimeSeriesResponseTableData(
                    rep_id = 2,
                    stat_name = "Hourly System Time",
                    period = 1,
                    start_time = 0.0,
                    end_time = 60.0,
                    length = 60.0,
                    value = 12.0
                ),
                TimeSeriesResponseTableData(
                    rep_id = 1,
                    stat_name = "Hourly System Time",
                    period = 2,
                    start_time = 60.0,
                    end_time = 120.0,
                    length = 60.0,
                    value = 14.0
                ),
                TimeSeriesResponseTableData(
                    rep_id = 2,
                    stat_name = "Hourly System Time",
                    period = 2,
                    start_time = 60.0,
                    end_time = 120.0,
                    length = 60.0,
                    value = 16.0
                )
            )
        )
    }

    private fun ReportNode.Document.renderToText(): String {
        val myRenderer = TextReportRenderer(RenderContext())
        accept(myRenderer)
        return myRenderer.result()
    }

    private fun ReportNode.countPlotNodes(): Int {
        return when (this) {
            is ReportNode.Document -> children.sumOf { it.countPlotNodes() }
            is ReportNode.Section -> children.sumOf { it.countPlotNodes() }
            is ReportNode.PlotNode -> 1
            else -> 0
        }
    }
}
