/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.app.single.results

import ksl.examples.book.appendixD.GIGcQueue
import ksl.observers.welch.WelchFileObserver
import ksl.simulation.Model
import ksl.testutils.DisabledIfHeadless
import ksl.utilities.io.OutputDirectory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 *  Phase-3 tests for [WelchReportMaterializer].
 *
 *  A helper runs a short simulation that streams real Welch data files
 *  to a temp directory (no plot rendering, so it is headless-safe).  The
 *  discovery and "no data" tests then run unguarded; the HTML-render test
 *  carries [DisabledIfHeadless] because the Welch report embeds lets-plot
 *  figures that throw `java.awt.HeadlessException` in a headless JVM.
 */
class WelchReportMaterializerTest {

    private companion object {
        const val MODEL_ID = "WelchMaterializerMM1"
        const val SYSTEM_TIME = "System Time"
        const val NUM_IN_SYSTEM = "Num in System"
    }

    // Close the Welch observers (their .wdf/.json write handles) and the analyzers (their .wdf read
    // handles) so the @TempDir can be deleted; on Windows an open handle blocks temp-dir cleanup.
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterEach
    fun closeOpened() {
        closeables.forEach { runCatching { it.close() } }
        closeables.clear()
    }

    /**
     * Builds a small GIGc model whose runtime output is rooted at
     * [outputDir], attaches Welch observers to its tally and time-weighted
     * responses, and runs a few short replications.  Leaves
     * `<outputDir>/<responseName>_Welch/` directories on disk.
     */
    private fun writeWelchData(outputDir: Path) {
        val model = Model(MODEL_ID, autoCSVReports = false)
        model.outputDirectory = OutputDirectory(outputDir, "kslOutput.txt")
        // Match Ch5Example4's run scale (5 reps x 50000) so each response
        // yields enough Welch averages for the analysis to batch (>= 2).
        model.numberOfReplications = 5
        model.lengthOfReplication = 50000.0
        GIGcQueue(model, numServers = 1, name = "Q")
        // Self-attaching observers — one tally, one time-weighted.
        closeables += WelchFileObserver(model.response(SYSTEM_TIME)!!, 1.0)
        closeables += WelchFileObserver(model.response(NUM_IN_SYSTEM)!!, 10.0)
        model.simulate()
    }

    @Test
    @DisplayName("discoverAnalyzers finds an analyzer per Welch directory")
    fun discoverAnalyzersFindsAnAnalyzerPerWelchDir(@TempDir tempDir: Path) {
        writeWelchData(tempDir)

        val analyzers = WelchReportMaterializer.discoverAnalyzers(tempDir).onEach { closeables += it }
        assertEquals(2, analyzers.size,
            "Expected one analyzer per captured response; got ${analyzers.map { it.responseName }}")
        val names = analyzers.map { it.responseName }.toSet()
        assertTrue(SYSTEM_TIME in names, "Missing '$SYSTEM_TIME'; got: $names")
        assertTrue(NUM_IN_SYSTEM in names, "Missing '$NUM_IN_SYSTEM'; got: $names")
    }

    @Test
    @DisplayName("materialize returns Failed when no Welch data is present")
    fun materializeReturnsFailedWhenNoWelchData(@TempDir tempDir: Path) {
        val analyzers = WelchReportMaterializer.discoverAnalyzers(tempDir) // empty dir
        assertTrue(analyzers.isEmpty(), "Pre-condition: no analyzers in an empty dir")

        val outcome = WelchReportMaterializer.materialize(
            analyzers = analyzers,
            format = StandardReportFormat.HTML,
            reportsDir = tempDir.resolve("reports")
        )
        assertIs<StandardReportOutcome.Failed>(outcome)
    }

    @Test
    @DisplayName("clearWelchData removes only the *_Welch dirs, leaving other output")
    fun clearWelchDataRemovesOnlyWelchDirs(@TempDir tempDir: Path) {
        // Two Welch capture dirs plus a non-Welch run-output dir.
        Files.createDirectories(tempDir.resolve("System Time_Welch"))
        Files.createDirectories(tempDir.resolve("Num in System_Welch").resolve("nested"))
        Files.createDirectories(tempDir.resolve("csvDir"))

        val removed = WelchReportMaterializer.clearWelchData(tempDir)

        assertEquals(2, removed, "both *_Welch dirs should be removed")
        assertFalse(Files.exists(tempDir.resolve("System Time_Welch")))
        assertFalse(Files.exists(tempDir.resolve("Num in System_Welch")))
        assertTrue(Files.exists(tempDir.resolve("csvDir")), "non-Welch output must survive")
    }

    @Test
    @DisplayName("clearWelchData is a no-op (returns 0) for an absent directory")
    fun clearWelchDataNoOpForAbsentDir(@TempDir tempDir: Path) {
        assertEquals(0, WelchReportMaterializer.clearWelchData(tempDir.resolve("does-not-exist")))
    }

    @Test
    @DisplayName("materialize HTML writes a non-empty file with a section per response")
    @DisabledIfHeadless
    fun materializeHtmlWritesSectionPerResponse(@TempDir tempDir: Path) {
        writeWelchData(tempDir)
        val analyzers = WelchReportMaterializer.discoverAnalyzers(tempDir).onEach { closeables += it }
        val reportsDir = tempDir.resolve("reports")

        val outcome = WelchReportMaterializer.materialize(
            analyzers = analyzers,
            format = StandardReportFormat.HTML,
            reportsDir = reportsDir,
            fileStem = "welch",
            title = "Warm-Up Analysis — $MODEL_ID"
        )

        val ok = assertIs<StandardReportOutcome.Ok>(outcome)
        assertTrue(ok.file.exists(), "Report file must exist: ${ok.file}")
        val html = ok.file.readText()
        assertTrue(html.isNotBlank(), "Report file must be non-empty")
        assertTrue(html.contains(SYSTEM_TIME), "HTML must contain a section for '$SYSTEM_TIME'")
        assertTrue(html.contains(NUM_IN_SYSTEM), "HTML must contain a section for '$NUM_IN_SYSTEM'")

        // The HTML renderer embeds each plot inline (base64/SVG) inside a
        // plot-container div — its presence confirms the Welch/partial-sums
        // plots actually rendered rather than silently dropping out.
        assertTrue(html.contains("plot-container"),
            "HTML must embed at least one plot (Welch / partial-sums)")
    }
}
