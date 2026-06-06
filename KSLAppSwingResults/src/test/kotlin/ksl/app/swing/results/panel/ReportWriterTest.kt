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

package ksl.app.swing.results.panel

import ksl.app.config.ReportFormat
import ksl.utilities.io.report.dsl.report
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Tests for [ReportWriter] — the module's multi-format document
 *  writer used by every analysis tab.  Headless: builds a trivial
 *  document and writes it to a temp directory; no browser is opened.
 */
class ReportWriterTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `writes one file per requested format`() {
        val doc = report("Trivial") { paragraph("hello") }
        val outcome = ReportWriter.write(
            doc, dir, "stem",
            setOf(ReportFormat.HTML, ReportFormat.MARKDOWN, ReportFormat.TEXT)
        )
        assertTrue(outcome.errors.isEmpty(), "unexpected errors: ${outcome.errors}")
        assertEquals(3, outcome.written.size)
        assertTrue(outcome.htmlPath?.toString()?.endsWith("stem.html") == true)
        for (p in outcome.written) assertTrue(Files.exists(p), "missing $p")
    }

    @Test
    fun `empty format set yields an error and writes nothing`() {
        val doc = report("Trivial") { paragraph("hello") }
        val outcome = ReportWriter.write(doc, dir, "stem", emptySet())
        assertTrue(outcome.written.isEmpty())
        assertTrue(outcome.errors.isNotEmpty())
    }

    @Test
    fun `stem sanitisation collapses filesystem-unsafe characters`() {
        assertEquals("System_Time_Sec", ReportWriter.sanitizeStem("System Time/Sec"))
    }
}
