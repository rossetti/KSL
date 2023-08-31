@file:Suppress("DuplicatedCode")

package ksl.utilities.io.plotting

import ksl.utilities.Interval
import ksl.utilities.distributions.CDFIfc
import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.distributions.InverseCDFIfc
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.StatisticReporter
import ksl.utilities.orderStatistics
import ksl.utilities.statistic.*
import org.jetbrains.letsPlot.Stat
import org.jetbrains.letsPlot.asDiscrete
import org.jetbrains.letsPlot.core.util.PlotHtmlExport
import org.jetbrains.letsPlot.core.util.PlotHtmlHelper
import org.jetbrains.letsPlot.export.VersionChecker
import org.jetbrains.letsPlot.export.ggsave
import org.jetbrains.letsPlot.geom.*
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.intern.toSpec
import org.jetbrains.letsPlot.label.ggtitle
import java.io.File
import java.nio.file.Path

abstract class PlotImp() : PlotIfc {

    override val plot: Plot
        get() = buildPlot()

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
        //TODO
        val spec = plot.toSpec()

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
        return KSLFileUtil.openInBrowser(fileName, html)
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
    private val referencePoint: Double? = null
) : PlotImp() {

    private val data: MutableMap<String, MutableList<Any>> = mutableMapOf()

    constructor(list: List<StatisticIfc>, level: Double = 0.95, referencePoint: Double? = null) :
            this(StatisticReporter.confidenceIntervals(list, level), referencePoint)

    init {
        data["yLabel"] = mutableListOf()
        data["upperLimit"] = mutableListOf()
        data["average"] = mutableListOf()
        data["lowerLimit"] = mutableListOf()
        for ((n, ci) in intervals) {
            data["yLabel"]!!.add(n)
            data["upperLimit"]!!.add(ci.upperLimit)
            data["average"]!!.add(ci.midPoint)
            data["lowerLimit"]!!.add(ci.lowerLimit)
        }
    }

    override fun buildPlot(): Plot {
        val p = ggplot(data) +
                geomErrorBar(stat = Stat.identity) {
                    y = asDiscrete("yLabel") // this causes x to be categorical and removes the x-axis scaling, etc.
                    width = "average"
                    xmin = "lowerLimit"
                    xmax = "upperLimit"
                } +
                geomPoint {
                    y = asDiscrete("yLabel")
                    x = "average"
                } + geomVLine(xintercept = referencePoint, color = "#de2d26", linetype = "dashed") +
                ggtitle(title) +
                ggsize(width, height)

        //TODO need to look into xlab() or themes() to remove x labels, need way to specify y label
        return p
    }

}

class IntegerFrequencyPlot(private val frequency: IntegerFrequency, proportions: Boolean = false) : PlotImp() {

    private val data: MutableMap<String, List<Any>> = mutableMapOf()
    private var dataType: String

    init {
        data["xLabel"] = frequency.values.asList()
        if (proportions) {
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

class StateFrequencyPlot(private val frequency: StateFrequency, proportions: Boolean = false) : PlotImp() {

    private val data: MutableMap<String, List<Any>> = mutableMapOf()
    private var dataType: String

    init {
        data["xLabel"] = frequency.stateNames
        if (proportions) {
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
                    x = "xLabel"
                    y = dataType
                } +
                ggtitle(title) +
                ggsize(width, height)

        //TODO need to look into xlab() or themes() to remove x labels, need way to specify y label
        return p
    }

}

class QQPlot(
    data: DoubleArray,
    private val quantileFunction: InverseCDFIfc
) : PlotImp() {

    var empDistType: EmpDistType = EmpDistType.Continuity1
    val orderStats = data.orderStatistics()

    val empProbabilities
        get() = Statistic.empDist(orderStats.size, empDistType)

    val quantiles: DoubleArray
        get() = DoubleArray(orderStats.size) { i -> quantileFunction.invCDF(empProbabilities[i]) }

    override fun buildPlot(): Plot {
        //TODO label axis
        return ScatterPlot(quantiles, orderStats).plot
    }

}

class PPPlot(
    data: DoubleArray,
    private val cdfFunction: CDFIfc
) : PlotImp() {

    var empDistType: EmpDistType = EmpDistType.Continuity1
    val orderStats = data.orderStatistics()

    val empProbabilities
        get() = Statistic.empDist(orderStats.size, empDistType)

    val theoreticalProbabilities: DoubleArray
        get() = DoubleArray(orderStats.size) { i -> cdfFunction.cdf(orderStats[i]) }

    override fun buildPlot(): Plot {
        //TODO label axis, add 45 degree line
        return ScatterPlot(theoreticalProbabilities, empProbabilities).plot
    }

}