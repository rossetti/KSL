@file:Suppress("DuplicatedCode")

package ksl.utilities.io.plotting

import ksl.observers.welch.WelchDataArrayObserver
import ksl.observers.welch.WelchDataFileAnalyzer
import ksl.utilities.Interval
import ksl.utilities.distributions.*
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.StatisticReporter
import ksl.utilities.math.FunctionIfc
import ksl.utilities.orderStatistics
import ksl.utilities.statistic.*
import ksl.utilities.statistics
import ksl.utilities.toDoubles
import org.jetbrains.kotlinx.dataframe.impl.asList
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
import org.jetbrains.letsPlot.label.labs
import org.jetbrains.letsPlot.label.ylab
import org.jetbrains.letsPlot.scale.ylim
import org.jetbrains.letsPlot.tooltips.layerTooltips
import java.io.File
import java.nio.file.Path
import kotlin.math.floor

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
        //val plot = buildPlot()
        println(this)
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
    override fun toString(): String {
        return "PlotImp(" +
                "defaultScale=$defaultScale, " +
                "defaultDPI=$defaultDPI, " +
                "width=$width, " +
                "height=$height, " +
                "title='$title', " +
                "xLabel='$xLabel', " +
                "yLabel='$yLabel'" +
                ")"
    }


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
        val p = ScatterPlot(quantiles, orderStats).plot +
                labs(x = "Theoretical Quantiles", y = "Empirical Quantiles")
        return p
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
        var p = ScatterPlot(theoreticalProbabilities, empProbabilities).plot +
                geomABLine(slope = 1.0, intercept = 0.0, color = "#de2d26") +
                labs(x = "Theoretical Probabilities", y = "Empirical Probabilities")
        return p
    }

}

class FunctionPlot(
    private val function: ((Double) -> Double),
    private val interval: Interval,
    numPoints: Int = 512
) : PlotImp() {

    var numPoints: Int = numPoints
        set(value) {
            require(value > 0) { "The number of points on the x-axis must be > 0" }
            field = value
        }

    override fun buildPlot(): Plot {
        val limits = Pair(interval.lowerLimit, interval.upperLimit)
        val p = ggplot(null) +
                geomFunction(xlim = limits, fn = function, n = numPoints) +
                ggtitle(title) +
                ggsize(width, height)
        return p
    }

}

class HistogramPlot(
    private val histogram: HistogramIfc,
    var proportions: Boolean = false
) : PlotImp() {

    var density: ((Double) -> Double)? = null
    var numPoints: Int = 512
        set(value) {
            require(value > 0) { "The number of points on the x-axis must be > 0" }
            field = value
        }

    override fun buildPlot(): Plot {
        val ul = histogram.upperLimits
        if (ul.last().isInfinite()) {
            ul[ul.lastIndex] = histogram.max + 1.0
        }
        val ll = histogram.lowerLimits
        if (ll.first().isInfinite()) {
            ll[0] = histogram.min - 1
        }
        val data = if (proportions) {
            yLabel = "Bin Proportions"
            mapOf(
                "xmin" to ll,
                "xmax" to ul,
                "ymax" to histogram.binFractions
            )
        } else {
            yLabel = "Bin Counts"
            mapOf<String, DoubleArray>(
                "xmin" to ll,
                "xmax" to ul,
                "ymax" to histogram.binCounts
            )
        }
        var p = ggplot() +
                geomRect(
                    data, ymin = 0.0,
                    tooltips = layerTooltips()
                        .format("xmin", ".1f")
                        .format("xmax", ".1f")
                        .line("@ymax")
                        .line("[@xmin, @xmax]")
                ) {
                    xmin = "xmin"
                    xmax = "xmax"
                    ymax = "ymax"
                }

        if (density != null) {
            val limits = Pair(ll[0], ul[ul.lastIndex])
            p = p + geomFunction(xlim = limits, fn = density, n = numPoints, color = "#de2d26")
        }
        p = p + ylab(yLabel) +
                ggtitle(title) +
                ggsize(width, height)
        return p
    }

}

class PartialSumsPlot(partialSums: DoubleArray, dataName: String? = null) : PlotImp() {

    private val data: Map<String, List<Number>>

    init {
        yLabel = "Partial Sums"
        xLabel = "Indices"
        title = if (dataName != null) "Partial Sums Plot for $dataName" else "Partial Sums Plot"
        data = mapOf(
            xLabel to (1..partialSums.size).asList(),
            yLabel to partialSums.asList()
        )
    }

    override fun buildPlot(): Plot {
        val p = ggplot(data) +
                geomLine() {
                    x = xLabel
                    y = yLabel
                } +
                ggtitle(title) +
                ggsize(width, height)
        return p
    }

}

class WelchPlot(avgs: DoubleArray, cumAvgs: DoubleArray, val responseName: String) : PlotImp() {

    private val data: Map<String, DoubleArray>

    constructor(dataArrayObserver: WelchDataArrayObserver) : this(
        avgs = dataArrayObserver.welchAverages,
        cumAvgs = dataArrayObserver.welchCumulativeAverages,
        responseName = dataArrayObserver.responseName
    ){
        val deltaT = dataArrayObserver.avgTimeBtwObservationsForEachReplication.statistics().average
        val ts = "%.2f".format(deltaT)
        title = "Welch Plot for $responseName, 1 obs = $ts time units"
    }

    constructor(analyzer: WelchDataFileAnalyzer, totalNumObservations: Int = analyzer.minNumObservationsInReplications.toInt()): this(
        avgs = analyzer.welchAveragesNE(totalNumObservations),
        cumAvgs = analyzer.cumulativeWelchAverages(totalNumObservations),
        responseName = analyzer.responseName
    ){
        val deltaT = analyzer.averageTimePerObservation
        val ts = "%.2f".format(deltaT)
        title = "Welch Plot for $responseName, 1 obs = $ts time units"
    }

    init {
        xLabel = "Observation Number"
        data = mapOf(
            xLabel to (1..avgs.size).toList().toIntArray().toDoubles(),
            "Welch Average" to avgs,
            "Cumulative Average" to cumAvgs
        )
    }

    override fun buildPlot(): Plot {
        val p = ggplot(data) +
                geomLine() {
                    x = xLabel
                    y = "Welch Average"
                } +
                geomLine(color = "#de2d26") {
                    x = xLabel
                    y = "Cumulative Average"
                }
        ggtitle(title) +
                ggsize(width, height)
        return p
    }

}

class StateVariablePlot(
    values: DoubleArray,
    times: DoubleArray,
    val responseName: String
): PlotImp(){
    private val data: Map<String, DoubleArray>

    init {
        xLabel = "t"
        yLabel = "y(t)"
        title = "Sample path for y(t)"
        data = mapOf(
            xLabel to times,
            yLabel to values
        )
    }

    override fun buildPlot(): Plot {
        println(this)
        val p = ggplot(data) +
                geomStep(){
                    x = xLabel
                    y = yLabel
                }
                geomPoint(color = "#de2d26") {
                    x = xLabel
                    y = yLabel
                } +
                ggtitle(title) +
                ggsize(width, height)
        return p
    }

}