package ksl.utilities.io.plotting

import ksl.utilities.distributions.DEmpiricalCDF
import ksl.utilities.statistic.Statistic
import org.jetbrains.kotlinx.dataframe.impl.asList
import org.jetbrains.letsPlot.Stat
import org.jetbrains.letsPlot.geom.*
import org.jetbrains.letsPlot.geom.extras.arrow
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs
import org.jetbrains.letsPlot.scale.ylim

class DiscreteCDFPlot(
    private val pmf: DEmpiricalCDF,
    dataName: String? = null
) : BasePlot() {

    private val dataMap: Map<String, List<Number>>

    private val v = pmf.values
    private val cp = pmf.cdf
    private val xEnd = DoubleArray(v.size - 1)

    init {
        yLabel = "Probability"
        xLabel = "Value"
        title = if (dataName != null) "CDF Plot for $dataName" else "CDF Plot"
        for (i in xEnd.indices) {
            xEnd[i] = v[i + 1]
        }
        this.dataMap = mapOf(
            "prob" to cp.asList().dropLast(1),
            "value" to v.asList().dropLast(1),
            "xEnd" to xEnd.asList()
        )
    }

    override fun buildPlot(): Plot {
        var p = ggplot(dataMap) +
                geomSegment(x= v[0], xend = (v[0]-1), y = 0.0, yend = 0, arrow = arrow(ends = "last")) +
                geomPoint(x = v[0], y = 0.0, shape = 1) +
                geomPoint(shape = 16) {
                    x = "value"
                    y = "prob"
                } +
                geomPoint(shape = 1) {
                    x = "xEnd"
                    y = "prob"
                } +
                geomPoint(x = v.last(), y = cp.last(), shape = 1) +
                geomSegment(x= v.last(), xend = (v.last()+1),
                    y = cp.last(), yend = cp.last(), arrow = arrow(ends = "last"))
        for (i in xEnd.indices) {
            p = p + geomSegment() {
                x = "value"
                y = "prob"
                xend = "xEnd"
                yend = "prob"
            }
        }
        p = p + labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}