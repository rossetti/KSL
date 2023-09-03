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
        val plot = buildPlot()
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
                labs(title = title, x = xLabel, y = yLabel) +
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
            "name" to List(1) { boxPlotSummary.name },
            "lowerWhisker" to List(1) { boxPlotSummary.lowerWhisker },
            "firstQuartile" to List(1) { boxPlotSummary.firstQuartile },
            "median" to List(1) { boxPlotSummary.median },
            "thirdQuartile" to List(1) { boxPlotSummary.thirdQuartile },
            "upperWhisker" to List(1) { boxPlotSummary.upperWhisker }
        )
    }

    constructor(data: DoubleArray, name: String? = null) : this(
        BoxPlotSummary(data, name)
    )

    override fun buildPlot(): Plot {
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
                    x = "name"
                    lower = "firstQuartile"
                    middle = "median"
                    upper = "thirdQuartile"
                    ymin = "lowerWhisker"
                    ymax = "upperWhisker"
                } +
                labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}

class MultiBoxPlot(private val boxPlotMap: Map<String, BoxPlotSummary>) : PlotImp() {

    private val data: MutableMap<String, MutableList<Any>> = mutableMapOf()

    init {
        data["distribution"] = mutableListOf()
        data["lowerWhisker"] = mutableListOf()
        data["firstQuartile"] = mutableListOf()
        data["median"] = mutableListOf()
        data["thirdQuartile"] = mutableListOf()
        data["upperWhisker"] = mutableListOf()
        for ((n, bps) in boxPlotMap) {
            data["distribution"]!!.add(n)
            data["lowerWhisker"]!!.add(bps.lowerWhisker)
            data["firstQuartile"]!!.add(bps.firstQuartile)
            data["median"]!!.add(bps.median)
            data["thirdQuartile"]!!.add(bps.thirdQuartile)
            data["upperWhisker"]!!.add(bps.upperWhisker)
        }
    }

    constructor(dataMap: Map<String, DoubleArray>) : this(Statistic.boxPlotSummaries(dataMap))

    override fun buildPlot(): Plot {
        val p = ggplot(data) +
                geomBoxplot(stat = Stat.identity) {
                    x = "distribution"
                    lower = "firstQuartile"
                    middle = "median"
                    upper = "thirdQuartile"
                    ymin = "lowerWhisker"
                    ymax = "upperWhisker"
                } +
                labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
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

    constructor(data: Map<String, DoubleArray>, level: Double = 0.95, referencePoint: Double? = null) :
            this(Statistic.confidenceIntervals(data, level), referencePoint)

    init {
        data["CI"] = mutableListOf()
        data["upperLimit"] = mutableListOf()
        data["average"] = mutableListOf()
        data["lowerLimit"] = mutableListOf()
        for ((n, ci) in intervals) {
            data["CI"]!!.add(n)
            data["upperLimit"]!!.add(ci.upperLimit)
            data["average"]!!.add(ci.midPoint)
            data["lowerLimit"]!!.add(ci.lowerLimit)
        }
    }

    override fun buildPlot(): Plot {
        val p = ggplot(data) +
                geomErrorBar(stat = Stat.identity) {
                    y = "CI"
                    width = "average"
                    xmin = "lowerLimit"
                    xmax = "upperLimit"
                } +
                geomPoint {
                    y = "CI"
                    x = "average"
                } + geomVLine(xintercept = referencePoint, color = "red", linetype = "dashed") +
                labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}

class IntegerFrequencyPlot(private val frequency: IntegerFrequency, proportions: Boolean = false) : PlotImp() {

    private val data: MutableMap<String, List<Any>> = mutableMapOf()
    private var dataType: String

    init {
        data["values"] = frequency.values.asList()
        if (proportions) {
            dataType = "proportions"
            data[dataType] = frequency.proportions.asList()
        } else {
            dataType = "counts"
            data[dataType] = frequency.frequencies.asList()
        }
        xLabel = "values"
        yLabel = dataType
    }

    override fun buildPlot(): Plot {
        val p = ggplot(data) +
                geomBar(stat = Stat.identity) {
                    x = asDiscrete("values") // this causes x to be categorical and removes the x-axis scaling, etc.
                    y = dataType
                } +
                labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}

class StateFrequencyPlot(private val frequency: StateFrequency, proportions: Boolean = false) : PlotImp() {

    private val data: MutableMap<String, List<Any>> = mutableMapOf()
    private var dataType: String

    init {
        data["states"] = frequency.stateNames
        if (proportions) {
            dataType = "proportions"
            data[dataType] = frequency.proportions.asList()
        } else {
            dataType = "counts"
            data[dataType] = frequency.frequencies.asList()
        }
        xLabel = "states"
        yLabel = dataType
    }

    override fun buildPlot(): Plot {
        val p = ggplot(data) +
                geomBar(stat = Stat.identity) {
                    x = "states"
                    y = dataType
                } +
                labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
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
        val sp = ScatterPlot(quantiles, orderStats)
        val p = sp.buildPlot() +
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
        val sp = ScatterPlot(theoreticalProbabilities, empProbabilities)
        val p = sp.buildPlot() +
                geomABLine(slope = 1.0, intercept = 0.0, color = "red") +
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
                labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}

class HistogramPlot(
    private val histogram: HistogramIfc,
    var proportions: Boolean = false
) : PlotImp() {

    private val data: Map<String, DoubleArray>
    private val lowerLimits: DoubleArray
    private val upperLimits: DoubleArray

    init {
        upperLimits = histogram.upperLimits
        if (upperLimits.last().isInfinite()) {
            upperLimits[upperLimits.lastIndex] = histogram.max + 1.0
        }
        lowerLimits = histogram.lowerLimits
        if (lowerLimits.first().isInfinite()) {
            lowerLimits[0] = histogram.min - 1
        }
        data = if (proportions) {
            yLabel = "Bin Proportions"
            mapOf(
                "xmin" to lowerLimits,
                "xmax" to upperLimits,
                "ymax" to histogram.binFractions
            )
        } else {
            yLabel = "Bin Counts"
            mapOf<String, DoubleArray>(
                "xmin" to lowerLimits,
                "xmax" to upperLimits,
                "ymax" to histogram.binCounts
            )
        }
    }

    constructor(data: DoubleArray, proportions: Boolean = false) :
            this(Histogram.create(data), proportions)

    var density: ((Double) -> Double)? = null
    var numPoints: Int = 512
        set(value) {
            require(value > 0) { "The number of points on the x-axis must be > 0" }
            field = value
        }

    override fun buildPlot(): Plot {
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
            val limits = Pair(lowerLimits[0], upperLimits[upperLimits.lastIndex])
            p = p + geomFunction(xlim = limits, fn = density, n = numPoints, color = "red")
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
            "indices" to (1..partialSums.size).asList(),
            "partial sums" to partialSums.asList()
        )
    }

    override fun buildPlot(): Plot {
        val p = ggplot(data) +
                geomLine() {
                    x = "indices"
                    y = "partial sums"
                } +
                labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}

class WelchPlot(avgs: DoubleArray, cumAvgs: DoubleArray, val responseName: String) : PlotImp() {

    private val data: Map<String, DoubleArray>

    init {
        xLabel = "Observation Number"
        yLabel = responseName
        title = "Welch Plot for $responseName"
        data = mapOf(
            "Observation Number" to (1..avgs.size).toList().toIntArray().toDoubles(),
            "Welch Average" to avgs,
            "Cumulative Average" to cumAvgs
        )
    }

    constructor(dataArrayObserver: WelchDataArrayObserver) : this(
        avgs = dataArrayObserver.welchAverages,
        cumAvgs = dataArrayObserver.welchCumulativeAverages,
        responseName = dataArrayObserver.responseName
    ) {
        val deltaT = dataArrayObserver.avgTimeBtwObservationsForEachReplication.statistics().average
        val ts = "%.2f".format(deltaT)
        title = "$title, 1 obs = $ts time units"
    }

    constructor(
        analyzer: WelchDataFileAnalyzer,
        totalNumObservations: Int = analyzer.minNumObservationsInReplications.toInt()
    ) : this(
        avgs = analyzer.welchAveragesNE(totalNumObservations),
        cumAvgs = analyzer.cumulativeWelchAverages(totalNumObservations),
        responseName = analyzer.responseName
    ) {
        val deltaT = analyzer.averageTimePerObservation
        val ts = "%.2f".format(deltaT)
        title = "$title, 1 obs = $ts time units"
    }

    override fun buildPlot(): Plot {
        val p = ggplot(data) +
                geomLine() {
                    x = "Observation Number"
                    y = "Welch Average"
                } +
                geomLine(color = "red") {
                    x = "Observation Number"
                    y = "Cumulative Average"
                } +
                labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}

class StateVariablePlot(
    values: DoubleArray,
    times: DoubleArray,
    val responseName: String
) : PlotImp() {
    private val data: Map<String, DoubleArray>

    init {
        xLabel = "t"
        yLabel = "y(t)"
        title = "Sample path for y(t)"
        data = mapOf(
            "times" to times,
            "values" to values
        )
    }

    override fun buildPlot(): Plot {
        val p = ggplot(data) +
                geomStep() {
                    x = "times"
                    y = "values"
                } +
                geomPoint(color = "red") {
                    x = "times"
                    y = "values"
                } +
                labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}