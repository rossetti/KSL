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
import ksl.app.notification.NotificationSink
import ksl.app.swing.common.io.openHtmlInBrowser
import ksl.app.swing.results.ResultsAppController
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.writeHtml
import ksl.utilities.io.report.writeMarkdown
import ksl.utilities.io.report.writeText
import java.awt.Component
import java.awt.FlowLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JCheckBox
import javax.swing.JPanel

/**
 *  Writes a [ReportNode.Document] to one or more files under a target
 *  directory.  Mirrors the multi-format writing path used by
 *  `ksl.app.results.comparison.ComparisonReportRenderer`, but for an
 *  already-assembled document.  Side-effect-bearing only — it never
 *  opens a viewer (the host decides that via [deliverReport]).
 */
object ReportWriter {

    data class Outcome(val written: List<Path>, val errors: List<String>) {
        val htmlPath: Path? get() = written.firstOrNull { it.toString().endsWith(".html") }
    }

    fun write(
        doc: ReportNode.Document,
        outputDir: Path,
        stem: String,
        formats: Set<ReportFormat>
    ): Outcome {
        if (formats.isEmpty()) return Outcome(emptyList(), listOf("No report formats selected."))
        Files.createDirectories(outputDir)
        val written = mutableListOf<Path>()
        val errors = mutableListOf<String>()
        for (fmt in formats) {
            try {
                val path = outputDir.resolve("$stem.${extensionFor(fmt)}")
                when (fmt) {
                    ReportFormat.HTML -> doc.writeHtml(path = path)
                    ReportFormat.MARKDOWN -> doc.writeMarkdown(path = path)
                    ReportFormat.TEXT -> doc.writeText(path = path)
                }
                written.add(path)
            } catch (t: Throwable) {
                errors.add("${fmt.name}: ${t.message ?: t::class.simpleName ?: "unknown error"}")
            }
        }
        return Outcome(written, errors)
    }

    /** Collapse filesystem-unsafe characters so a response/experiment
     *  name is safe as a file stem. */
    fun sanitizeStem(stem: String): String =
        stem.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)

    private fun extensionFor(fmt: ReportFormat): String = when (fmt) {
        ReportFormat.HTML -> "html"
        ReportFormat.MARKDOWN -> "md"
        ReportFormat.TEXT -> "txt"
    }
}

/**
 *  Write [doc] in the chosen [formats] to the controller's current
 *  output directory and, when [open] is `true`, open the HTML in the
 *  browser.  All user-facing outcomes flow through [notifier]; nothing
 *  is thrown to the caller.
 */
internal fun deliverReport(
    controller: ResultsAppController,
    notifier: NotificationSink,
    doc: ReportNode.Document,
    stem: String,
    formats: Set<ReportFormat>,
    open: Boolean
) {
    if (formats.isEmpty()) {
        notifier.warn("Select at least one output format.")
        return
    }
    val outputDir = controller.outputDir
    val outcome = ReportWriter.write(doc, outputDir, ReportWriter.sanitizeStem(stem), formats)
    for (e in outcome.errors) notifier.error(e)
    if (outcome.written.isEmpty()) return

    val htmlPath = outcome.htmlPath
    if (open && htmlPath != null) {
        try {
            openHtmlInBrowser(htmlPath)
        } catch (t: Throwable) {
            notifier.warn("Wrote report but could not open a browser: ${t.message ?: t::class.simpleName}")
        }
    }
    notifier.info("Wrote ${outcome.written.size} file(s) to $outputDir")
}

/** Left-aligned row of components, used to lay out the analysis tabs'
 *  control strips. */
internal fun controlRow(vararg components: Component): JPanel =
    JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { for (c in components) add(c) }

/**
 *  Small reusable strip of output-format checkboxes (HTML, Markdown,
 *  Text).  HTML is checked by default — it is the only format that
 *  renders the embedded plots.
 */
class FormatChooser : JPanel() {
    private val html = JCheckBox("HTML", true)
    private val markdown = JCheckBox("Markdown", false)
    private val text = JCheckBox("Text", false)

    init {
        add(html)
        add(markdown)
        add(text)
    }

    fun selectedFormats(): Set<ReportFormat> = buildSet {
        if (html.isSelected) add(ReportFormat.HTML)
        if (markdown.isSelected) add(ReportFormat.MARKDOWN)
        if (text.isSelected) add(ReportFormat.TEXT)
    }
}
