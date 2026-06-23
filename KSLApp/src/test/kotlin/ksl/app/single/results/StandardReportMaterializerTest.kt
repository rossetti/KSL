package ksl.app.single.results

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.app.KSLAppSession
import ksl.app.RunSpec
import ksl.app.config.ModelReference
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.app.config.toOverrides
import ksl.app.session.RunResult
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Substrate-level tests for [StandardReportMaterializer].
 *
 *  Submits a tiny single run via `KSLAppSession.submit(RunSpec.Single(...))`
 *  — the exact path any non-Swing UI shell would take — and
 *  verifies that the materialiser writes the three supported
 *  formats and surfaces failures as typed outcomes.
 */
class StandardReportMaterializerTest {

    private companion object {
        const val MM1_ID = "MM1"
        const val TIMEOUT_MS = 30_000L
    }

    private val mm1Provider = MapModelProvider(MM1_ID, object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model {
            val model = Model(MM1_ID, autoCSVReports = false)
            model.numberOfReplications = 3
            model.lengthOfReplication = 100.0
            GIGcQueue(model, numServers = 1, name = "Q")
            return model
        }
    })

    private fun mm1Config(): RunConfiguration {
        val model = mm1Provider.provideModel(MM1_ID)
        return RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "single",
                    modelReference = ModelReference.ByProviderId(MM1_ID),
                    runOverrides = model.extractRunParameters().toOverrides()
                )
            )
        )
    }

    private fun runOnce(): RunResult.Completed = runBlocking {
        KSLAppSession(mm1Provider, this).use { session ->
            val handle = session.submit(RunSpec.Single(mm1Config()))
            val result = withTimeout(TIMEOUT_MS) { handle.result.await() }
            assertIs<RunResult.Completed>(result)
            result
        }
    }

    @Test
    fun `extractSnapshot returns the snapshot on Completed`() {
        val result = runOnce()
        val snapshot = StandardReportMaterializer.extractSnapshot(result)
        assertTrue(snapshot != null, "Completed result must yield a snapshot")
    }

    @Test
    fun `materialize HTML writes a report file with html extension`(@TempDir tempDir: Path) {
        val result = runOnce()
        val outcome = StandardReportMaterializer.materialize(
            result = result,
            format = StandardReportFormat.HTML,
            reportsDir = tempDir.resolve("reports"),
            fileStem = "standard"
        )
        assertIs<StandardReportOutcome.Ok>(outcome)
        assertTrue(outcome.file.exists(), "Written file must exist")
        assertTrue(outcome.file.name.endsWith(".html"))
        val content = Files.readString(outcome.file.toPath())
        assertTrue(content.isNotBlank(), "HTML body must not be empty")
    }

    @Test
    fun `materialize Markdown and Text write distinct files`(@TempDir tempDir: Path) {
        val result = runOnce()
        val md = StandardReportMaterializer.materialize(
            result = result,
            format = StandardReportFormat.MARKDOWN,
            reportsDir = tempDir.resolve("reports"),
            fileStem = "standard"
        )
        val txt = StandardReportMaterializer.materialize(
            result = result,
            format = StandardReportFormat.TEXT,
            reportsDir = tempDir.resolve("reports"),
            fileStem = "standard"
        )
        assertIs<StandardReportOutcome.Ok>(md)
        assertIs<StandardReportOutcome.Ok>(txt)
        assertTrue(md.file.name.endsWith(".md"))
        assertTrue(txt.file.name.endsWith(".txt"))
        // Both end up in the same reports directory.
        assertEquals(md.file.parentFile, txt.file.parentFile)
    }

    @Test
    fun `materialize fails cleanly on a Failed run result`(@TempDir tempDir: Path) {
        // A non-completed result has no snapshot to render — the
        // materialiser must surface that as a typed Failed outcome
        // rather than throwing.
        val cancelled = RunResult.Cancelled(reason = "test fixture")
        val outcome = StandardReportMaterializer.materialize(
            result = cancelled,
            format = StandardReportFormat.HTML,
            reportsDir = tempDir.resolve("reports")
        )
        assertIs<StandardReportOutcome.Failed>(outcome)
        // No file must have been written.
        assertTrue(!tempDir.resolve("reports/standard.html").toFile().exists())
    }

    @Test
    fun `extractSnapshot returns null on a Cancelled result`() {
        val cancelled = RunResult.Cancelled(reason = "fixture")
        assertNull(StandardReportMaterializer.extractSnapshot(cancelled))
    }

    @Test
    fun `StandardReportFormat fromButtonLabel matches case-insensitively`() {
        assertEquals(StandardReportFormat.HTML, StandardReportFormat.fromButtonLabel("HTML"))
        assertEquals(StandardReportFormat.HTML, StandardReportFormat.fromButtonLabel("html"))
        assertEquals(StandardReportFormat.MARKDOWN, StandardReportFormat.fromButtonLabel("Markdown"))
        assertEquals(StandardReportFormat.TEXT, StandardReportFormat.fromButtonLabel("Text"))
        assertNull(StandardReportFormat.fromButtonLabel("LaTeX"))
    }
}
