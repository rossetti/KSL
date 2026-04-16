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
import java.nio.file.Path

class BoxPlot(private val boxPlotSummary: BoxPlotSummary) : BasePlot() {

    private val data: Map<String, Any>
    private val outliers: Map<String, List<Any>>

    init {
        // looks like data must be in lists and column names
        // are used to the mapping to plot aesthetics
        data = mapOf(
            "name"          to List(1) { boxPlotSummary.name },
            "lowerWhisker"  to List(1) { maxOf(boxPlotSummary.lowerWhisker, boxPlotSummary.min) },
            "firstQuartile" to List(1) { boxPlotSummary.firstQuartile },
            "median"        to List(1) { boxPlotSummary.median },
            "thirdQuartile" to List(1) { boxPlotSummary.thirdQuartile },
            "upperWhisker"  to List(1) { minOf(boxPlotSummary.upperWhisker, boxPlotSummary.max) },
            "yMin"          to List(1) { boxPlotSummary.min },
            "yMax"          to List(1) { boxPlotSummary.max }
        )
        // Collect all four outlier categories into a single list, each paired with the
        // box's x-position name so geomPoint can place the dots over the correct box.
        val myValues = boxPlotSummary.lowerOuterPoints().asList().toMutableList()
        myValues.addAll(boxPlotSummary.pointsBtwLowerInnerAndOuterFences().asList())
        myValues.addAll(boxPlotSummary.pointsBtwUpperInnerAndOuterFences().asList())
        myValues.addAll(boxPlotSummary.upperOuterPoints().asList())
        outliers = mapOf(
            "name"     to List(myValues.size) { boxPlotSummary.name },
            "outliers" to myValues
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
           the data map is then part of the generated html.

           Outlier points are rendered as open circles (shape = 1) via a separate
           geomPoint layer.  coordCartesian clips the y-axis to a window centred on
           the whisker range (padded by half the IQR) so that extreme outliers do not
           compress the box into a sliver.  Points outside the window are simply not
           drawn; they remain documented in the report's Outlier Summary table.
         */
        val myPad   = 0.5 * boxPlotSummary.interQuartileRange
        val myYLow  = boxPlotSummary.lowerWhisker - myPad
        val myYHigh = boxPlotSummary.upperWhisker + myPad

        val p = ggplot(data) +
                geomBoxplot(stat = Stat.identity, whiskerWidth = 0.05) {
                    x      = "name"
                    lower  = "firstQuartile"
                    middle = "median"
                    upper  = "thirdQuartile"
                    ymin   = "lowerWhisker"
                    ymax   = "upperWhisker"
                } +
                geomPoint(outliers, shape = 1) {
                    x = "name"
                    y = "outliers"
                } +
                coordCartesian(ylim = Pair(myYLow, myYHigh)) +
                labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}
