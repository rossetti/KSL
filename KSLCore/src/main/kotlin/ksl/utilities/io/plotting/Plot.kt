package ksl.utilities.io.plotting

import ksl.utilities.io.KSL
import ksl.utilities.statistic.BoxPlotSummary
import org.jetbrains.letsPlot.Stat
import org.jetbrains.letsPlot.asDiscrete
import org.jetbrains.letsPlot.core.util.PlotHtmlExport
import org.jetbrains.letsPlot.core.util.PlotHtmlHelper
import org.jetbrains.letsPlot.export.VersionChecker
import org.jetbrains.letsPlot.export.ggsave
import org.jetbrains.letsPlot.geom.geomBoxplot
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.intern.layer.StatOptions
import org.jetbrains.letsPlot.intern.toSpec
import org.jetbrains.letsPlot.label.ggtitle
import java.awt.Desktop
import java.io.File
import java.io.FileWriter
import java.nio.file.Path

abstract class PlotImp() : PlotIfc {

    override var defaultScale: Int = 2
        set(value) {
            require(value > 0) { "The scale must be > 0" }
            field = value
        }
    override var defaultDPI: Int = 144
        set(value) {
            require(value > 0) { "The DPI must be > 0" }
            field = value
        }
    override var width: Int = 500
        set(value) {
            require(value > 0) { "The width must be > 0" }
            field = value
        }
    override var height: Int = 350
        set(value) {
            require(value > 0) { "The height must be > 0" }
            field = value
        }

    override var title: String = ""
    override var xLabel: String = "x"
    override var yLabel: String = "y"

    protected fun openInBrowser(file: File) {
        val desktop = Desktop.getDesktop()
        desktop.browse(file.toURI())
    }

    override fun saveToFile(
        fileName: String,
        directory: Path,
        plotTitle: String,
        extType: PlotIfc.ExtType
    ): File {
        title = plotTitle
        val plot = buildPlot()
        val fn = fileName + "." + extType.name
        val pn = ggsave(plot, fn, defaultScale, defaultDPI, directory.toString())
        return File(pn)
    }

    override fun showInBrowser(plotTitle: String): File {
        title = plotTitle
        val p = buildPlot()
        //TODO
        val spec = p.toSpec()

        // Export: use PlotHtmlExport utility to generate dynamic HTML (optionally in iframe).
        val html = PlotHtmlExport.buildHtmlFromRawSpecs(
            spec, iFrame = true,
            scriptUrl = PlotHtmlHelper.scriptUrl(VersionChecker.letsPlotJsVersion)
        )
        val fileName = if (title.isEmpty()) {
            "tempPlotFile_"
        } else {
            title.replace(" ", "_") + "_"
        }
        return openInBrowser(fileName, html)
    }

    private fun openInBrowser(fileName: String, html: String): File {
        val file = createTemporaryFile(fileName)
        FileWriter(file).use {
            it.write(html)
        }
        openInBrowser(file)
        return file
    }

    private fun createTemporaryFile(fileName: String): File {
        val tmpDir = File(KSL.plotDir.toString())
        if (!tmpDir.exists()) {
            tmpDir.mkdir()
        }
        return File.createTempFile(fileName, ".html", tmpDir)
    }

    protected abstract fun buildPlot(): Plot
}

internal class ScatterPlot(
    x: DoubleArray,
    y: DoubleArray,
) : PlotImp() {

    private val data: Map<String, DoubleArray>

    init {
        data = mapOf(
            xLabel to x,
            yLabel to y
        )
    }

    override fun buildPlot(): Plot {
        val p = ggplot(data) +
                geomPoint() {
                    x = xLabel
                    y = yLabel
                } +
                ggtitle(title) +
                ggsize(width, height)
        return p
    }
}

internal class BoxPlot(private val boxPlotSummary: BoxPlotSummary) : PlotImp() {

    private val data: Map<String, Any>

    init {
        // looks like data must be in lists and column names
        // are used to the mapping to plot aesthetics
        data = mapOf(
            "xLabel" to List(1) {"boxPlot"},
            "lowerWhisker" to List(1) { boxPlotSummary.lowerWhisker },
            "firstQuartile" to List(1) { boxPlotSummary.firstQuartile },
            "median" to List(1) { boxPlotSummary.median },
            "thirdQuartile" to List(1) { boxPlotSummary.thirdQuartile },
            "upperWhisker" to List(1) { boxPlotSummary.upperWhisker }
        )
    }

    override fun buildPlot(): Plot {
        // this works by directly assigning to geomBoxplot() parameters
        // but does not allow plot to be "live"  with data in generated html

//        val p = ggplot(null) +
//                geomBoxplot(stat = Stat.identity,
//                    lower = boxPlotSummary.firstQuartile,
//                    middle = boxPlotSummary.median,
//                    upper = boxPlotSummary.thirdQuartile,
//                    ymin = boxPlotSummary.lowerWhisker,
//                    ymax = boxPlotSummary.upperWhisker)  +
//                ggtitle(title) +
//                ggsize(width, height)

        /* Stat.identity prevents the statistical transformation of the data.
           The data values represent the already computed box plot aesthetics.
           Note that in the geomBoxplot() closure, we define the mapping
           from data to the aesthetics of the boxplot. Note that the string
           referencing the data is assigned to the aesthetic mapping so that
           the geomBoxplot() function can extract the required values.  Somehow
           the data map is then part of the generated html
         */
        val p = ggplot(data) +
                geomBoxplot(stat = Stat.identity) {
                    x = asDiscrete("xLabel") // this causes x to be categorical and removes the x-axis scaling, etc.
                    lower = "firstQuartile"
                    middle = "median"
                    upper = "thirdQuartile"
                    ymin = "lowerWhisker"
                    ymax = "upperWhisker"
                } +
                ggtitle(title) +
                ggsize(width, height)

        //TODO need to look into xlab() or themes() to remove x labels
        return p
    }

}

