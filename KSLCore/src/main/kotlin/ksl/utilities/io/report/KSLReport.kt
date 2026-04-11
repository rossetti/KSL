/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.utilities.io.report

import ksl.utilities.io.KSL
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.renderer.HtmlReportRenderer
import ksl.utilities.io.report.renderer.LaTeXReportRenderer
import ksl.utilities.io.report.renderer.MarkdownReportRenderer
import ksl.utilities.io.report.renderer.RenderContext
import ksl.utilities.io.report.renderer.TextReportRenderer
import java.io.File
import java.nio.file.Path

/**
 * Convenience extension functions on [ReportNode.Document] for rendering to
 * HTML, Markdown, plain text, and LaTeX in a single call.
 *
 * Each function creates the appropriate renderer, traverses the AST via
 * [ReportNode.Document.accept], and writes the result to a file.  File paths
 * default to [KSL.outDir] using the document title sanitised for use as a
 * filename; callers may supply any [Path] instead.
 *
 * Usage:
 * ```kotlin
 * model.simulate()
 * val doc = model.buildReport("Drive-Through Pharmacy")
 * doc.showInBrowser()
 * doc.writeMarkdown()
 * doc.writeText()
 * doc.writeLaTeX()
 * ```
 */

/**
 * Renders this document to an HTML file and returns the written [File].
 *
 * @param path output file path; defaults to `KSL.outDir/<sanitised-title>.html`
 * @param ctx  shared render configuration (output directory, plot directory,
 *             confidence level, numeric precision, max plots per section)
 */
fun ReportNode.Document.writeHtml(
    path: Path = KSL.outDir.resolve("${title.toSafeFileName()}.html"),
    ctx: RenderContext = RenderContext()
): File {
    val myRenderer = HtmlReportRenderer(ctx)
    accept(myRenderer)
    return myRenderer.writeToFile(path)
}

/**
 * Renders this document to a Markdown file and returns the written [File].
 *
 * @param path output file path; defaults to `KSL.outDir/<sanitised-title>.md`
 * @param ctx  shared render configuration
 */
fun ReportNode.Document.writeMarkdown(
    path: Path = KSL.outDir.resolve("${title.toSafeFileName()}.md"),
    ctx: RenderContext = RenderContext()
): File {
    val myRenderer = MarkdownReportRenderer(ctx)
    accept(myRenderer)
    return myRenderer.writeTo(path)
}

/**
 * Renders this document to a plain-text file and returns the written [File].
 *
 * @param path output file path; defaults to `KSL.outDir/<sanitised-title>.txt`
 * @param ctx  shared render configuration
 */
fun ReportNode.Document.writeText(
    path: Path = KSL.outDir.resolve("${title.toSafeFileName()}.txt"),
    ctx: RenderContext = RenderContext()
): File {
    val myRenderer = TextReportRenderer(ctx)
    accept(myRenderer)
    return myRenderer.writeTo(path)
}

/**
 * Renders this document to a LaTeX source file and returns the written [File].
 *
 * @param path output file path; defaults to `KSL.outDir/<sanitised-title>.tex`
 * @param ctx  shared render configuration
 */
fun ReportNode.Document.writeLaTeX(
    path: Path = KSL.outDir.resolve("${title.toSafeFileName()}.tex"),
    ctx: RenderContext = RenderContext()
): File {
    val myRenderer = LaTeXReportRenderer(ctx)
    accept(myRenderer)
    return myRenderer.writeTo(path)
}

/**
 * Renders this document to HTML, writes it to a temporary file in
 * [ctx].[outputDir][RenderContext.outputDir], opens the file in the system
 * default browser, and returns the written [File].
 *
 * @param ctx shared render configuration; [RenderContext.outputDir] determines
 *            where the temporary file is placed
 */
fun ReportNode.Document.showInBrowser(ctx: RenderContext = RenderContext()): File {
    val myRenderer = HtmlReportRenderer(ctx)
    accept(myRenderer)
    return myRenderer.showInBrowser()
}

// ── Private helpers ───────────────────────────────────────────────────────────

/**
 * Converts a document title to a safe filename fragment by replacing runs of
 * non-alphanumeric characters with underscores, collapsing multiple underscores,
 * trimming leading/trailing underscores, and truncating to 60 characters.
 */
private fun String.toSafeFileName(): String = this
    .replace(Regex("[^A-Za-z0-9]+"), "_")
    .trim('_')
    .take(60)
    .ifEmpty { "report" }
