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

package ksl.utilities.io.report.extensions

import ksl.examples.book.appendixD.GIGcQueue
import ksl.observers.welch.WelchDataFileAnalyzer
import ksl.observers.welch.WelchFileObserver
import ksl.simulation.Model
import ksl.utilities.io.OutputDirectory
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.report
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Tests for the deletion-point gating added to
 *  [ksl.utilities.io.report.extensions.welchAnalysis].  Inspects the report
 *  AST (no rendering), so it is headless-safe — building plot nodes does not
 *  rasterize them.
 */
class WelchReportExtensionsTest {

    // Close the Welch writer (its .wdf/.json handles) and the analyzer (its .wdf read handle) so the
    // @TempDir can be deleted; on Windows an open handle blocks temp-dir cleanup (invisible on Unix).
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterEach
    fun closeOpened() {
        closeables.forEach { runCatching { it.close() } }
        closeables.clear()
    }

    /** Run a short sim with one tally response and return the analyzer. */
    private fun analyzerFor(outDir: Path): WelchDataFileAnalyzer {
        val m = Model("WelchExtModel", autoCSVReports = false)
        m.outputDirectory = OutputDirectory(outDir, "kslOutput.txt")
        m.numberOfReplications = 3
        m.lengthOfReplication = 1000.0
        GIGcQueue(m, numServers = 1, name = "Q")
        closeables += WelchFileObserver(m.response("System Time")!!, 1.0)
        m.simulate()
        val json = Files.list(outDir.resolve("System Time_Welch")).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".json") }.findFirst().orElseThrow()
        }
        return WelchDataFileAnalyzer.makeFromJSON(json).also { closeables += it }
    }

    /** All DataTable captions in the document, walking sections recursively. */
    private fun captions(node: ReportNode): List<String> = when (node) {
        is ReportNode.Document -> node.children.flatMap { captions(it) }
        is ReportNode.Section -> node.children.flatMap { captions(it) }
        is ReportNode.DataTable -> listOfNotNull(node.caption)
        else -> emptyList()
    }

    /** All Paragraph texts in the document, walking sections recursively. */
    private fun paragraphs(node: ReportNode): List<String> = when (node) {
        is ReportNode.Document -> node.children.flatMap { paragraphs(it) }
        is ReportNode.Section -> node.children.flatMap { paragraphs(it) }
        is ReportNode.Paragraph -> listOf(node.text)
        else -> emptyList()
    }

    @Test
    @DisplayName("includeDeletionPoint = true renders the MSER deletion-point table")
    fun deletionPointTablePresentWhenIncluded(@TempDir tempDir: Path) {
        val analyzer = analyzerFor(tempDir)
        val doc = report("Warm-Up") {
            welchAnalysis(analyzer, includePartialSums = false, includeDeletionPoint = true)
        }
        val caps = captions(doc)
        assertTrue(caps.any { it.contains("Deletion") },
            "expected a deletion-point table; captions were: $caps")
        // Sanity: the per-replication summary is always present.
        assertTrue(caps.any { it.contains("Per-Replication Summary") })
    }

    @Test
    @DisplayName("includeDeletionPoint = false omits the deletion-point table")
    fun deletionPointTableAbsentWhenExcluded(@TempDir tempDir: Path) {
        val analyzer = analyzerFor(tempDir)
        val doc = report("Warm-Up") {
            welchAnalysis(analyzer, includePartialSums = false, includeDeletionPoint = false)
        }
        val caps = captions(doc)
        assertFalse(caps.any { it.contains("Deletion") },
            "deletion-point table must be omitted; captions were: $caps")
        // The rest of the section is unaffected.
        assertTrue(caps.any { it.contains("Per-Replication Summary") })
    }

    @Test
    @DisplayName("bias test with deletionPoint = -1 tests the full series (from observation 1), not the MSER point")
    fun biasTestUsesFullSeriesByDefault(@TempDir tempDir: Path) {
        val analyzer = analyzerFor(tempDir)
        val doc = report("Warm-Up") {
            welchAnalysis(
                analyzer,
                includePartialSums = false,
                includeBiasTest = true,
                includeDeletionPoint = false,
                deletionPoint = -1
            )
        }
        // deletePt = 0 -> "from observation 1 onward".  This proves the bias
        // test does not use the MSER recommendation when none is supplied.
        assertTrue(paragraphs(doc).any { it.contains("from observation 1 onward") },
            "bias test should test the full series; paragraphs: ${paragraphs(doc)}")
    }

    @Test
    @DisplayName("bias test honors an explicit deletion point without computing MSER")
    fun biasTestHonorsExplicitDeletionPoint(@TempDir tempDir: Path) {
        val analyzer = analyzerFor(tempDir)
        val doc = report("Warm-Up") {
            welchAnalysis(
                analyzer,
                includePartialSums = false,
                includeBiasTest = true,
                includeDeletionPoint = false,
                deletionPoint = 50
            )
        }
        // deletePt = 50 -> "from observation 51 onward" — the user's value,
        // not an MSER recommendation.
        assertTrue(paragraphs(doc).any { it.contains("from observation 51 onward") },
            "bias test should honor the explicit deletion point; paragraphs: ${paragraphs(doc)}")
    }
}
