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
import ksl.utilities.io.OutputDirectory
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.renderer.HtmlReportRenderer
import ksl.utilities.io.report.renderer.LaTeXReportRenderer
import ksl.utilities.io.report.renderer.MarkdownReportRenderer
import ksl.utilities.io.report.renderer.RenderContext
import ksl.utilities.io.report.renderer.TextReportRenderer
import java.io.File
import java.io.PrintWriter
import java.nio.file.Path

/**
 * Convenience extension functions on [ReportNode.Document] for rendering to
 * HTML, Markdown, plain text, and LaTeX in a single call.
 *
 * Each function creates the appropriate renderer, traverses the AST via
 * [ReportNode.Document.accept], and writes the result to a file.  File paths
 * default to the document's default output directory when one was supplied,
 * otherwise [KSL.outDir], using the document title sanitised for use as a
 * filename. Callers may supply any [Path] instead.
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
 * **CSS selection** (evaluated in priority order):
 * 1. [cssPath] non-null → `<link rel="stylesheet" href="...">` is emitted; no inline `<style>` block
 * 2. [css] non-null → the supplied string is inlined in a `<style>` block
 * 3. Both null (default) → the built-in KSL stylesheet is inlined
 *
 * @param path    output file path; defaults to `ctx.outputDir/<sanitised-title>.html`
 * @param ctx     shared render configuration (output directory, plot directory,
 *                confidence level, numeric precision, max plots per section)
 * @param cssPath path to an external CSS file; when non-null a `<link>` tag is emitted
 *                and [css] is ignored
 * @param css     inline CSS string to embed in a `<style>` block; only used when
 *                [cssPath] is `null`; when both are `null` the built-in stylesheet is used
 */
fun ReportNode.Document.writeHtml(
    path: Path? = null,
    ctx: RenderContext? = null,
    cssPath: Path? = null,
    css: String? = null
): File {
    val myCtx = ctx ?: defaultRenderContext()
    val myRenderer = HtmlReportRenderer(myCtx, cssPath, css)
    accept(myRenderer)
    return myRenderer.writeToFile(path ?: defaultPath(myCtx, "html"))
}

/**
 * Renders this document to a Markdown file and returns the written [File].
 *
 * @param path output file path; defaults to `ctx.outputDir/<sanitised-title>.md`
 * @param ctx  shared render configuration
 */
fun ReportNode.Document.writeMarkdown(
    path: Path? = null,
    ctx: RenderContext? = null
): File {
    val myCtx = ctx ?: defaultRenderContext()
    val myRenderer = MarkdownReportRenderer(myCtx)
    accept(myRenderer)
    return myRenderer.writeTo(path ?: defaultPath(myCtx, "md"))
}

/**
 * Renders this document to a plain-text file and returns the written [File].
 *
 * @param path output file path; defaults to `ctx.outputDir/<sanitised-title>.txt`
 * @param ctx  shared render configuration
 */
fun ReportNode.Document.writeText(
    path: Path? = null,
    ctx: RenderContext? = null
): File {
    val myCtx = ctx ?: defaultRenderContext()
    val myRenderer = TextReportRenderer(myCtx)
    accept(myRenderer)
    return myRenderer.writeTo(path ?: defaultPath(myCtx, "txt"))
}

/**
 * Renders this document to a LaTeX source file and returns the written [File].
 *
 * @param path output file path; defaults to `ctx.outputDir/<sanitised-title>.tex`
 * @param ctx  shared render configuration
 */
fun ReportNode.Document.writeLaTeX(
    path: Path? = null,
    ctx: RenderContext? = null
): File {
    val myCtx = ctx ?: defaultRenderContext()
    val myRenderer = LaTeXReportRenderer(myCtx)
    accept(myRenderer)
    return myRenderer.writeTo(path ?: defaultPath(myCtx, "tex"))
}

/**
 * Renders this document to HTML, writes it to a temporary file in
 * [ctx].[outputDir][RenderContext.outputDir], opens the file in the system
 * default browser, and returns the written [File].
 *
 * **CSS selection** (evaluated in priority order):
 * 1. [cssPath] non-null → `<link rel="stylesheet" href="...">` is emitted; no inline `<style>` block
 * 2. [css] non-null → the supplied string is inlined in a `<style>` block
 * 3. Both null (default) → the built-in KSL stylesheet is inlined
 *
 * @param ctx     shared render configuration; [RenderContext.outputDir] determines
 *                where the temporary file is placed
 * @param cssPath path to an external CSS file; when non-null a `<link>` tag is emitted
 *                and [css] is ignored
 * @param css     inline CSS string to embed in a `<style>` block; only used when
 *                [cssPath] is `null`; when both are `null` the built-in stylesheet is used
 */
fun ReportNode.Document.showInBrowser(
    ctx: RenderContext? = null,
    cssPath: Path? = null,
    css: String? = null
): File {
    val myCtx = ctx ?: defaultRenderContext()
    val myRenderer = HtmlReportRenderer(myCtx, cssPath, css)
    accept(myRenderer)
    return myRenderer.showInBrowser()
}

// ── Console / string rendering ────────────────────────────────────────────────

/**
 * Renders this document to a plain-text [String] without creating any file.
 *
 * Useful for console output, logging, or test assertions:
 * ```kotlin
 * println(model.toReport().toText())
 * logger.info(mca.toReport().toText())
 * assertThat(stat.toReport().toText()).contains("Average")
 * ```
 *
 * @param ctx shared render configuration
 * @return the complete plain-text representation of this document
 */
fun ReportNode.Document.toText(ctx: RenderContext? = null): String {
    val myRenderer = TextReportRenderer(ctx ?: defaultRenderContext())
    accept(myRenderer)
    return myRenderer.output()
}

/**
 * Renders this document to a Markdown [String] without creating any file.
 *
 * Useful for embedding in larger strings, piping to a markdown-aware terminal,
 * or logging:
 * ```kotlin
 * println(model.toReport().toMarkdown())
 * markdownLogger.log(mca.toReport().toMarkdown())
 * ```
 *
 * @param ctx shared render configuration
 * @return the complete Markdown representation of this document
 */
fun ReportNode.Document.toMarkdown(ctx: RenderContext? = null): String {
    val myRenderer = MarkdownReportRenderer(ctx ?: defaultRenderContext())
    accept(myRenderer)
    return myRenderer.output()
}

/**
 * Renders this document as plain text and writes it to [out].
 *
 * Defaults to [System.out] with auto-flush, making it a direct console-output call:
 * ```kotlin
 * model.toReport().printText()                         // → System.out
 * model.toReport().printText(PrintWriter(System.err))  // → System.err
 * model.toReport().printText(myWriter)                 // → any PrintWriter
 * ```
 *
 * @param out target writer; defaults to a [PrintWriter] wrapping [System.out]
 * @param ctx shared render configuration
 */
fun ReportNode.Document.printText(
    out: PrintWriter = PrintWriter(System.out, true),
    ctx: RenderContext? = null
) {
    val myRenderer = TextReportRenderer(ctx ?: defaultRenderContext())
    accept(myRenderer)
    myRenderer.writeTo(out)
}

/**
 * Renders this document as Markdown and writes it to [out].
 *
 * Defaults to [System.out] with auto-flush:
 * ```kotlin
 * model.toReport().printMarkdown()          // → System.out
 * model.toReport().printMarkdown(myWriter)  // → any PrintWriter
 * ```
 *
 * @param out target writer; defaults to a [PrintWriter] wrapping [System.out]
 * @param ctx shared render configuration
 */
fun ReportNode.Document.printMarkdown(
    out: PrintWriter = PrintWriter(System.out, true),
    ctx: RenderContext? = null
) {
    val myRenderer = MarkdownReportRenderer(ctx ?: defaultRenderContext())
    accept(myRenderer)
    myRenderer.writeTo(out)
}

/**
 * Creates a [RenderContext] whose output and plot directories are rooted in this
 * [OutputDirectory].
 */
fun OutputDirectory.toRenderContext(
    confidenceLevel: Double = 0.95,
    numericPrecision: Int = 4,
    maxPlotsPerSection: Int = 20
): RenderContext {
    return RenderContext(
        outputDir = outDir,
        plotDir = plotDir,
        confidenceLevel = confidenceLevel,
        numericPrecision = numericPrecision,
        maxPlotsPerSection = maxPlotsPerSection
    )
}

// ── Private helpers ───────────────────────────────────────────────────────────

private fun ReportNode.Document.defaultRenderContext(): RenderContext {
    return RenderContext(
        outputDir = defaultOutputDir ?: KSL.outDir,
        plotDir = defaultPlotDir ?: defaultOutputDir?.resolve("plotDir") ?: KSL.plotDir
    )
}

private fun ReportNode.Document.defaultPath(ctx: RenderContext, extension: String): Path {
    return ctx.outputDir.resolve("${title.toSafeFileName()}.$extension")
}

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
