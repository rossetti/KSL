package ksl.utilities.io.plotting

import ksl.utilities.io.KSL
import ksl.utilities.io.KSLFileUtil
import org.jetbrains.letsPlot.Figure
import org.jetbrains.letsPlot.core.util.PlotHtmlExport
import org.jetbrains.letsPlot.core.util.PlotHtmlHelper
import org.jetbrains.letsPlot.export.VersionChecker
import org.jetbrains.letsPlot.export.ggsave
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.intern.toSpec
import java.io.File
import java.nio.file.Path

interface PlotIfc {

    enum class ExtType {
        PNG, JPEG, HTML, TIF, SVG
    }

    var defaultPlotDir: Path

    /** the scale associated with the plot **/
    var defaultScale: Int

    /**
     *  the dots per inch for the plot
     */
    var defaultDPI: Int

    /**
     * The width of the container holding the plot
     */
    var width: Int

    /**
     *  The height of the container holding the plot
     */
    var height: Int

    /**
     *  The title of the plot
     */
    var title: String

    /**
     *  the label for the x-axis
     */
    var xLabel: String

    /**
     *  the label for the y-axis
     */
    var yLabel: String

    /**
     *  Builds a new instance of a Lets-Plot representation
     *  of the plot
     */
    fun buildPlot(): Plot

    /**
     * An HTML representation of the plot for rendering as a standalone page (uses iFrame).
     */
    fun toHTML(): String

    /**
     * Returns an embeddable HTML fragment (a `<div>` and inline `<script>`) suitable
     * for compositing into a larger HTML page. Unlike [toHTML], this does not include
     * `<html>`, `<head>`, or `<body>` wrappers.
     *
     * **The caller must load the Lets-Plot JS library once in the enclosing page's `<head>`**,
     * via `PlotHtmlHelper.scriptUrl`, or the fragment renders as an empty box. A page built by
     * `ksl.utilities.io.report.renderer.HtmlReportRenderer` already does this. Use
     * [toSelfContainedHTML] instead when the output has to stand on its own.
     */
    fun toEmbeddedHTML(): String

    /**
     * Returns a complete HTML document for the plot that carries its own copy of the Lets-Plot JS
     * library, so it renders wherever it is put without the surrounding page doing anything.
     *
     * Differs from [toHTML] only in that the plot is not wrapped in an iframe. Prefer
     * [toEmbeddedHTML] for anything being composited into a larger page: a document nested inside
     * another document is invalid, and though browsers recover from it, other tools that consume
     * HTML need not.
     */
    fun toSelfContainedHTML(): String

    /**
     * @param fileName the name of the file without an extension
     * @param directory the path to the directory to contain the file
     * @param plotTitle the title of the plot if different from title property
     * @param extType the type of file, defaults to PNG
     * @return a File reference to the created file
     */
    fun saveToFile(
        fileName: String,
        directory: Path = defaultPlotDir,
        plotTitle: String = title,
        extType: ExtType = ExtType.PNG
    ): File

    /** Opens up a browser window and shows the contents of the plot within
     *  the browser.  A temporary file is created to represent the plot for display
     *  within the browser.
     *
     * @param plotTitle the title of the plot if different from the title property
     * @return a File reference to the created file
     */
    fun showInBrowser(plotTitle: String = title): File

    companion object {
        /**
         *  Shows a lets-plot plot in a browser window
         */
        fun showPlotInBrowser(figure: Figure, tmpFileName: String? = null, directory: Path = KSL.plotDir): File {
            val html = toHTML(figure)
            val fileName = if (tmpFileName == null) {
                "tempPlotFile_"
            } else {
                tmpFileName.replace(" ", "_") + "_"
            }
            return KSLFileUtil.openInBrowser(fileName, html, directory)
        }

        fun toHTML(figure: Figure) : String {
            val spec = figure.toSpec()
            // Export: use PlotHtmlExport utility to generate dynamic HTML (optionally in iframe).
            val html = PlotHtmlExport.buildHtmlFromRawSpecs(
                spec, iFrame = true,
                scriptUrl = PlotHtmlHelper.scriptUrl(VersionChecker.letsPlotJsVersion)
            )
            return html
        }

        /**
         * Produces an embeddable HTML fragment from a [Figure] — a `<div>` and inline
         * `<script>` with no surrounding page structure. The caller is responsible for
         * loading the Lets-Plot JS library once in the enclosing page's `<head>` via
         * [PlotHtmlHelper.scriptUrl].
         *
         * This used to return what [toSelfContainedHTML] now returns: a complete document,
         * `<html>`, `<head>`, `<body>` and its own library script. A report embedding several plots
         * therefore emitted one document per plot inside the page document. Browsers recover from
         * that — the plots render and the parsed DOM comes out right — but the markup is invalid,
         * every plot container ends up holding the flattened head of its nested document, and the
         * function could not be used for the one thing it exists for.
         */
        fun toEmbeddedHTML(figure: Figure): String {
            return PlotHtmlHelper.getDynamicDisplayHtmlForRawSpec(figure.toSpec())
        }

        /**
         * Produces a complete HTML document from a [Figure] that carries its own copy of the
         * Lets-Plot JS library, so it renders wherever it is put. Differs from [toHTML] only in
         * that the plot is not wrapped in an iframe.
         */
        fun toSelfContainedHTML(figure: Figure): String {
            val spec = figure.toSpec()
            return PlotHtmlExport.buildHtmlFromRawSpecs(
                spec, iFrame = false,
                scriptUrl = PlotHtmlHelper.scriptUrl(VersionChecker.letsPlotJsVersion)
            )
        }

        /**
         *  Saves the supplied plot to a file
         */
        fun saveToFile(
            figure: Figure,
            fileName: String,
            directory: Path = KSL.plotDir,
            extType: ExtType = ExtType.PNG,
            defaultScale: Int = 2,
            defaultDPI: Int = 144
        ): File {
            val fn = fileName + "." + extType.name
            val pn = ggsave(figure, fn, defaultScale, defaultDPI, directory.toString())
            return File(pn)
        }
    }

}