package ksl.utilities.io.plotting

import ksl.utilities.io.KSL
import ksl.utilities.io.KSLFileUtil
import org.jetbrains.letsPlot.core.util.PlotHtmlExport
import org.jetbrains.letsPlot.core.util.PlotHtmlHelper
import org.jetbrains.letsPlot.export.VersionChecker
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
        fun showPlotInBrowser(plot: Plot, tmpFileName: String? = null, directory: Path = KSL.plotDir): File {
            val spec = plot.toSpec()
            // Export: use PlotHtmlExport utility to generate dynamic HTML (optionally in iframe).
            val html = PlotHtmlExport.buildHtmlFromRawSpecs(
                spec, iFrame = true,
                scriptUrl = PlotHtmlHelper.scriptUrl(VersionChecker.letsPlotJsVersion)
            )
            val fileName = if (tmpFileName == null) {
                "tempPlotFile_"
            } else {
                tmpFileName.replace(" ", "_") + "_"
            }
            return KSLFileUtil.openInBrowser(fileName, html, directory)
        }
    }

}