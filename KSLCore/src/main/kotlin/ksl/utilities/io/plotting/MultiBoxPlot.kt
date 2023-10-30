package ksl.utilities.io.plotting

import ksl.utilities.statistic.BoxPlotSummary
import org.jetbrains.letsPlot.Stat
import org.jetbrains.letsPlot.geom.geomBoxplot
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs

class MultiBoxPlot(boxPlotMap: Map<String, BoxPlotSummary>) : BasePlot() {

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
// TODO causes platform crash same signature??
//    constructor(dataMap: Map<String, DoubleArray>) : this(Statistic.boxPlotSummaries(dataMap))

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