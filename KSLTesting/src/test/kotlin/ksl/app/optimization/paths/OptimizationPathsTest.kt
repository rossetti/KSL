package ksl.app.optimization.paths

import ksl.app.config.optimization.CoolingScheduleSpec
import ksl.app.config.optimization.SolverSpec
import ksl.app.config.optimization.SolverTrackingSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Substrate-level tests for [OptimizationPaths] — the pure
 *  filesystem-path helpers used by any UI shell to honour the
 *  canonical run-output directory layout
 *  (`<appWorkspace>/output/<analysisName>/run-NNN/`).
 *
 *  These tests have no Swing dependency and live in KSLTesting
 *  alongside the other substrate-level tests.
 */
class OptimizationPathsTest {

    // ── outputDir ────────────────────────────────────────────────

    @Test
    fun `outputDir resolves under appWorkspace`(@TempDir tempDir: Path) {
        val out = OptimizationPaths.outputDir(tempDir, "MyAnalysis")
        assertEquals(tempDir.resolve("output").resolve("MyAnalysis"), out)
    }

    @Test
    fun `outputDir sanitizes the analysis name`(@TempDir tempDir: Path) {
        val out = OptimizationPaths.outputDir(tempDir, "Bad/Name With Spaces")
        assertTrue(out.toString().endsWith("Bad_Name_With_Spaces"),
            "Sanitization should replace unsafe characters; got $out")
    }

    // ── traceFilePath ────────────────────────────────────────────

    @Test
    fun `traceFilePath returns null when tracking is disabled`(@TempDir tempDir: Path) {
        val path = OptimizationPaths.traceFilePath(
            runOutputDir = tempDir.resolve("run-001"),
            trackingSpec = SolverTrackingSpec(enableCsvTrace = false),
            solverSpec = SolverSpec.StochasticHillClimbing(
                maxIterations = 1, replicationsPerEvaluation = 1
            )
        )
        assertNull(path)
    }

    @Test
    fun `traceFilePath uses csvFileName when set`(@TempDir tempDir: Path) {
        val runDir = tempDir.resolve("run-001")
        val path = OptimizationPaths.traceFilePath(
            runOutputDir = runDir,
            trackingSpec = SolverTrackingSpec(enableCsvTrace = true, csvFileName = "custom"),
            solverSpec = SolverSpec.StochasticHillClimbing(
                maxIterations = 1, replicationsPerEvaluation = 1
            )
        )
        assertNotNull(path)
        assertEquals(runDir.resolve("custom.csv"), path)
    }

    @Test
    fun `traceFilePath falls back to solver name when csvFileName is null`(@TempDir tempDir: Path) {
        val runDir = tempDir.resolve("run-001")
        val path = OptimizationPaths.traceFilePath(
            runOutputDir = runDir,
            trackingSpec = SolverTrackingSpec(enableCsvTrace = true, csvFileName = null),
            solverSpec = SolverSpec.StochasticHillClimbing(
                maxIterations = 1, replicationsPerEvaluation = 1, name = "my-shc"
            )
        )
        assertNotNull(path)
        assertEquals(runDir.resolve("my-shc_trace.csv"), path)
    }

    @Test
    fun `traceFilePath falls back to kind label when solver name is null`(@TempDir tempDir: Path) {
        val runDir = tempDir.resolve("run-001")
        val path = OptimizationPaths.traceFilePath(
            runOutputDir = runDir,
            trackingSpec = SolverTrackingSpec(enableCsvTrace = true, csvFileName = null),
            solverSpec = SolverSpec.SimulatedAnnealing(
                maxIterations = 1,
                replicationsPerEvaluation = 1,
                coolingSchedule = CoolingScheduleSpec.Logarithmic(10.0),
                stoppingTemperature = 0.1
            )
        )
        assertNotNull(path)
        assertEquals(runDir.resolve("simulatedAnnealing_trace.csv"), path)
    }

    @Test
    fun `traceFilePath uses 'solver' fallback when solver spec is null`(@TempDir tempDir: Path) {
        val runDir = tempDir.resolve("run-001")
        val path = OptimizationPaths.traceFilePath(
            runOutputDir = runDir,
            trackingSpec = SolverTrackingSpec(enableCsvTrace = true, csvFileName = null),
            solverSpec = null
        )
        assertNotNull(path)
        assertEquals(runDir.resolve("solver_trace.csv"), path)
    }

    // ── nextRunSubdir ────────────────────────────────────────────

    @Test
    fun `nextRunSubdir returns run-001 when analysis dir is missing`(@TempDir tempDir: Path) {
        val analysisDir = tempDir.resolve("MyAnalysis")  // not created
        val next = OptimizationPaths.nextRunSubdir(analysisDir)
        assertEquals(analysisDir.resolve("run-001"), next)
    }

    @Test
    fun `nextRunSubdir returns run-001 when analysis dir is empty`(@TempDir tempDir: Path) {
        val analysisDir = tempDir.resolve("MyAnalysis").also { Files.createDirectories(it) }
        val next = OptimizationPaths.nextRunSubdir(analysisDir)
        assertEquals(analysisDir.resolve("run-001"), next)
    }

    @Test
    fun `nextRunSubdir picks the next number when run-NNN subdirs exist`(@TempDir tempDir: Path) {
        val analysisDir = tempDir.resolve("MyAnalysis").also { Files.createDirectories(it) }
        Files.createDirectories(analysisDir.resolve("run-001"))
        Files.createDirectories(analysisDir.resolve("run-002"))
        Files.createDirectories(analysisDir.resolve("run-007"))
        Files.createDirectories(analysisDir.resolve("not-a-run"))
        val next = OptimizationPaths.nextRunSubdir(analysisDir)
        assertEquals(analysisDir.resolve("run-008"), next)
    }
}
