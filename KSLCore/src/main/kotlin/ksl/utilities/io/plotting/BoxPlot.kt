package ksl.utilities.io.plotting

import ksl.utilities.statistic.BoxPlotSummary
import org.jetbrains.letsPlot.Stat
import org.jetbrains.letsPlot.geom.geomBoxplot
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs

class BoxPlot(private val boxPlotSummary: BoxPlotSummary) : BasePlot() {

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