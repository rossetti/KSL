package ksl.utilities.io.plotting

import ksl.utilities.statistic.BoxPlotSummary
import org.jetbrains.letsPlot.Stat
import org.jetbrains.letsPlot.coord.coordCartesian
import org.jetbrains.letsPlot.geom.geomBoxplot
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs

class MultiBoxPlot(boxPlotMap: Map<String, BoxPlotSummary>) : BasePlot() {

    private val data: MutableMap<String, MutableList<Any>> = mutableMapOf()

    // Outlier points: one row per outlier value, tagged with its distribution name
    // so geomPoint places each dot above the correct box on the x-axis.
    private val outlierData: MutableMap<String, MutableList<Any>> = mutableMapOf()

    // y-axis clip boundaries, computed from the global whisker range across all boxes
    private val globalYLow:  Double
    private val globalYHigh: Double

    init {
        data["distribution"]  = mutableListOf()
        data["lowerWhisker"]  = mutableListOf()
        data["firstQuartile"] = mutableListOf()
        data["median"]        = mutableListOf()
        data["thirdQuartile"] = mutableListOf()
        data["upperWhisker"]  = mutableListOf()

        outlierData["distribution"] = mutableListOf()
        outlierData["value"]        = mutableListOf()

        var myMinWhisker = Double.MAX_VALUE
        var myMaxWhisker = -Double.MAX_VALUE
        var myMaxIQR     = 0.0

        for ((n, bps) in boxPlotMap) {
            data["distribution"]!!.add(n)
            data["lowerWhisker"]!!.add(bps.lowerWhisker)
            data["firstQuartile"]!!.add(bps.firstQuartile)
            data["median"]!!.add(bps.median)
            data["thirdQuartile"]!!.add(bps.thirdQuartile)
            data["upperWhisker"]!!.add(bps.upperWhisker)

            // Collect all four outlier categories for this distribution
            val myOutliers = mutableListOf<Double>()
            myOutliers.addAll(bps.lowerOuterPoints().asList())
            myOutliers.addAll(bps.pointsBtwLowerInnerAndOuterFences().asList())
            myOutliers.addAll(bps.pointsBtwUpperInnerAndOuterFences().asList())
            myOutliers.addAll(bps.upperOuterPoints().asList())
            for (v in myOutliers) {
                outlierData["distribution"]!!.add(n)
                outlierData["value"]!!.add(v)
            }

            // Track global whisker extents and maximum IQR for axis padding
            if (bps.lowerWhisker    < myMinWhisker) myMinWhisker = bps.lowerWhisker
            if (bps.upperWhisker    > myMaxWhisker) myMaxWhisker = bps.upperWhisker
            if (bps.interQuartileRange > myMaxIQR)  myMaxIQR     = bps.interQuartileRange
        }

        val myPad    = 0.5 * myMaxIQR
        globalYLow   = myMinWhisker - myPad
        globalYHigh  = myMaxWhisker + myPad
    }

// TODO causes platform crash same signature??
//    constructor(dataMap: Map<String, DoubleArray>) : this(Statistic.boxPlotSummaries(dataMap))

    override fun buildPlot(): Plot {
        /* Stat.identity uses pre-computed quartile statistics.
           Outlier points are rendered as open circles (shape = 1) via a separate
           geomPoint layer keyed on the "distribution" column.
           coordCartesian clips the y-axis to the whisker range plus half-IQR padding
           so that extreme outliers do not compress the boxes visually; points outside
           the window are simply not drawn.
         */
        val p = ggplot(data) +
                geomBoxplot(stat = Stat.identity) {
                    x      = "distribution"
                    lower  = "firstQuartile"
                    middle = "median"
                    upper  = "thirdQuartile"
                    ymin   = "lowerWhisker"
                    ymax   = "upperWhisker"
                } +
                geomPoint(outlierData, shape = 1) {
                    x = "distribution"
                    y = "value"
                } +
                coordCartesian(ylim = Pair(globalYLow, globalYHigh)) +
                labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}
