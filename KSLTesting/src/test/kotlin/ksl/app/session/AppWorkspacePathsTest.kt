package ksl.app.session

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 *  Substrate tests for [AppWorkspacePaths] — pure path-resolution
 *  primitives, no filesystem touch required.
 */
class AppWorkspacePathsTest {

    private val workspace: Path = Path.of("/home/user/ksl-workspace")

    @Test
    fun `sanitizeAppName replaces spaces with underscores`() {
        assertEquals("KSL_Single", AppWorkspacePaths.sanitizeAppName("KSL Single"))
        assertEquals("KSL_Experiment", AppWorkspacePaths.sanitizeAppName("KSL Experiment"))
        assertEquals("KSL_Sim_Opt", AppWorkspacePaths.sanitizeAppName("KSL Sim Opt"))
    }

    @Test
    fun `sanitizeAppName is idempotent on already-safe input`() {
        assertEquals("KSLScenarioApp", AppWorkspacePaths.sanitizeAppName("KSLScenarioApp"))
        assertEquals("app-1_0.2", AppWorkspacePaths.sanitizeAppName("app-1_0.2"))
    }

    @Test
    fun `sanitizeAppName preserves empty input`() {
        assertEquals("", AppWorkspacePaths.sanitizeAppName(""))
    }

    @Test
    fun `appWorkspaceDir resolves under active workspace with sanitization applied`() {
        val expected = workspace.resolve("KSL_Scenario")
        assertEquals(expected, AppWorkspacePaths.appWorkspaceDir(workspace, "KSL Scenario"))
    }

    @Test
    fun `appWorkspaceDir works with multi-segment workspace paths`() {
        val nested = Path.of("/var/lib/ksl/users/alice/workspace")
        val expected = nested.resolve("KSL_Experiment")
        assertEquals(expected, AppWorkspacePaths.appWorkspaceDir(nested, "KSL Experiment"))
    }

    @Test
    fun `outputDir nests under output with analysisName sanitization`() {
        // sanitizeAnalysisName replaces unsafe characters with '_'.
        val appWs = workspace.resolve("KSL_Experiment")
        val out = AppWorkspacePaths.outputDir(appWs, "Queueing Study #1")
        assertEquals(appWs.resolve("output").resolve("Queueing_Study__1"), out)
    }

    @Test
    fun `outputDir preserves already-safe analysisName`() {
        val appWs = workspace.resolve("KSL_Scenario")
        val out = AppWorkspacePaths.outputDir(appWs, "baseline-2026")
        assertEquals(appWs.resolve("output").resolve("baseline-2026"), out)
    }

    @Test
    fun `reportsDir composes outputDir with a reports subdirectory`() {
        val appWs = workspace.resolve("KSL_Simopt")
        val reports = AppWorkspacePaths.reportsDir(appWs, "tuning-run")
        assertEquals(appWs.resolve("output").resolve("tuning-run").resolve("reports"), reports)
    }

    @Test
    fun `reportsDir applies analysisName sanitization same as outputDir`() {
        val appWs = workspace.resolve("KSL_Experiment")
        val reports = AppWorkspacePaths.reportsDir(appWs, "stress test/v2")
        // 'stress test/v2' → spaces/slash replaced by sanitizeAnalysisName
        val out = AppWorkspacePaths.outputDir(appWs, "stress test/v2")
        assertEquals(out.resolve("reports"), reports)
    }

    @Test
    fun `appWorkspaceDir then outputDir then reportsDir compose end-to-end`() {
        // The canonical multi-document-app layout:
        //   <active-workspace>/<sanitizeAppName(appName)>/output/<sanitizeAnalysisName(name)>/reports/
        val ws = AppWorkspacePaths.appWorkspaceDir(workspace, "KSL Scenario")
        val out = AppWorkspacePaths.outputDir(ws, "baseline")
        val reports = AppWorkspacePaths.reportsDir(ws, "baseline")
        assertEquals(
            workspace.resolve("KSL_Scenario").resolve("output").resolve("baseline"),
            out
        )
        assertEquals(out.resolve("reports"), reports)
    }
}
