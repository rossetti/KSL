package ksl.utilities.io.plotting

import ksl.utilities.statistic.BoxPlotSummary
import org.jetbrains.letsPlot.Stat
import org.jetbrains.letsPlot.geom.geomBoxplot
import org.jetbrains.letsPlot.geom.geomJitter
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs
import java.nio.file.Path

class BoxPlot(private val boxPlotSummary: BoxPlotSummary) : BasePlot() {

    private val data: Map<String, Any>
    private val outliers: Map<String, List<Double>>

    init {
        // looks like data must be in lists and column names
        // are used to the mapping to plot aesthetics
        data = mapOf(
            "name" to List(1) { boxPlotSummary.name },
            "lowerWhisker" to List(1) { maxOf(boxPlotSummary.lowerWhisker, boxPlotSummary.min) },
            "firstQuartile" to List(1) { boxPlotSummary.firstQuartile },
            "median" to List(1) { boxPlotSummary.median },
            "thirdQuartile" to List(1) { boxPlotSummary.thirdQuartile },
            "upperWhisker" to List(1) { minOf(boxPlotSummary.upperWhisker, boxPlotSummary.max) },
            "yMin" to List(1){boxPlotSummary.min},
            "yMax" to List(1){boxPlotSummary.max}
        )
        val list = boxPlotSummary.lowerOuterPoints().asList().toMutableList()
        list.addAll(boxPlotSummary.pointsBtwLowerInnerAndOuterFences().asList())
        list.addAll(boxPlotSummary.pointsBtwUpperInnerAndOuterFences().asList())
        list.addAll(boxPlotSummary.upperOuterPoints().asList())
        outliers = mapOf(
            "outliers" to list
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
//        val dm = mapOf<String, List<Double>>("data" to boxPlotSummary.data)
        //TODO show outlier points on the plot: Issue -> large outliers make weird scaling of boxes
//        val p = ggplot(dm){y = "data"} + geomBoxplot () +
                val p = ggplot(data) +
                        geomBoxplot(stat = Stat.identity, whiskerWidth = 0.05) {
                            x = "name"
                            lower = "firstQuartile"
                            middle = "median"
                            upper = "thirdQuartile"
                            ymin = "lowerWhisker"
                            ymax = "upperWhisker"
                        } +
//                geomPoint(outliers){ y = "outliers"} +
                labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}