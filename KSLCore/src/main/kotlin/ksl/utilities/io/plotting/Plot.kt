@file:Suppress("DuplicatedCode")

package ksl.utilities.io.plotting

import ksl.utilities.Interval
import ksl.utilities.io.KSL
import ksl.utilities.statistic.BoxPlotSummary
import ksl.utilities.statistic.IntegerFrequency
import ksl.utilities.statistic.StatisticIfc
import org.jetbrains.letsPlot.Stat
import org.jetbrains.letsPlot.asDiscrete
import org.jetbrains.letsPlot.core.util.PlotHtmlExport
import org.jetbrains.letsPlot.core.util.PlotHtmlHelper
import org.jetbrains.letsPlot.export.VersionChecker
import org.jetbrains.letsPlot.export.ggsave
import org.jetbrains.letsPlot.geom.geomBar
import org.jetbrains.letsPlot.geom.geomBoxplot
import org.jetbrains.letsPlot.geom.geomErrorBar
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

class ScatterPlot(
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

class BoxPlot(private val boxPlotSummary: BoxPlotSummary) : PlotImp() {

    private val data: Map<String, Any>

    init {
        // looks like data must be in lists and column names
        // are used to the mapping to plot aesthetics
        data = mapOf(
            "xLabel" to List(1) { boxPlotSummary.name },
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

        //TODO need to look into xlab() or themes() to remove x labels, need way to specify y label
        return p
    }

}

class MultiBoxPlot(private val boxPlotMap: Map<String, BoxPlotSummary>) : PlotImp() {

    private val data: MutableMap<String, MutableList<Any>> = mutableMapOf()

    init {
        data["xLabel"] = mutableListOf()
        data["lowerWhisker"] = mutableListOf()
        data["firstQuartile"] = mutableListOf()
        data["median"] = mutableListOf()
        data["thirdQuartile"] = mutableListOf()
        data["upperWhisker"] = mutableListOf()
        for ((n, bps) in boxPlotMap) {
            data["xLabel"]!!.add(n)
            data["lowerWhisker"]!!.add(bps.lowerWhisker)
            data["firstQuartile"]!!.add(bps.firstQuartile)
            data["median"]!!.add(bps.median)
            data["thirdQuartile"]!!.add(bps.thirdQuartile)
            data["upperWhisker"]!!.add(bps.upperWhisker)
        }
    }

    override fun buildPlot(): Plot {
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

        //TODO need to look into xlab() or themes() to remove x labels, need way to specify y label
        return p
    }

}

class ConfidenceIntervalsPlot(
    private val intervals: Map<String, Interval>,
    private val referencePoint: Double = Double.NaN
) : PlotImp() {

    private val data: MutableMap<String, MutableList<Any>> = mutableMapOf()

 //TODO   constructor(list: List<StatisticIfc>, level: Double = 0.95, referencePoint: Double = Double.NaN): this()

    init {
        data["xLabel"] = mutableListOf()
        data["upperLimit"] = mutableListOf()
        data["average"] = mutableListOf()
        data["lowerLimit"] = mutableListOf()
        for ((n, ci) in intervals) {
            data["xLabel"]!!.add(n)
            data["upperLimit"]!!.add(ci.upperLimit)
            data["average"]!!.add(ci.midPoint)
            data["lowerLimit"]!!.add(ci.lowerLimit)
        }
    }
    override fun buildPlot(): Plot {
        val p = ggplot(data) +
                geomErrorBar(stat = Stat.identity) {
                    x = asDiscrete("xLabel") // this causes x to be categorical and removes the x-axis scaling, etc.
                    height = "average"
                    ymin = "lowerLimit"
                    ymax = "upperLimit"
                } +
                geomPoint {
                    x = asDiscrete("xLabel")
                    y = "average"
                }
                ggtitle(title) +
                ggsize(width, height)

        //TODO need to look into xlab() or themes() to remove x labels, need way to specify y label
        return p
    }

}

class FrequencyPlot(private val frequency: IntegerFrequency, proportions: Boolean = false) : PlotImp() {

    private val data: MutableMap<String, List<Any>> = mutableMapOf()
    private var dataType: String
    init{
        data["xLabel"] = frequency.values.asList()
        if (proportions){
            dataType = "proportions"
            data[dataType] = frequency.proportions.asList()
        } else {
            dataType = "counts"
            data[dataType] = frequency.frequencies.asList()
        }

    }
    override fun buildPlot(): Plot {
        val p = ggplot(data) +
                geomBar(stat = Stat.identity) {
                    x = asDiscrete("xLabel") // this causes x to be categorical and removes the x-axis scaling, etc.
                    y = dataType
                } +
                ggtitle(title) +
                ggsize(width, height)

        //TODO need to look into xlab() or themes() to remove x labels, need way to specify y label
        return p
    }

}