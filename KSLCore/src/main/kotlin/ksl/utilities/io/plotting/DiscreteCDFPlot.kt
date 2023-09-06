package ksl.utilities.io.plotting

import ksl.utilities.distributions.DEmpiricalCDF
import ksl.utilities.statistic.Statistic
import org.jetbrains.kotlinx.dataframe.impl.asList
import org.jetbrains.letsPlot.Stat
import org.jetbrains.letsPlot.geom.*
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs
import org.jetbrains.letsPlot.scale.ylim

class DiscreteCDFPlot(
    private val pmf : DEmpiricalCDF,
    dataName: String? = null
) : BasePlot() {

    private val dataMap: Map<String, List<Number>>
    private val p = pmf.probPoints
    private val v = pmf.values
    private val cp = pmf.cdf
    private val xEnd = DoubleArray(p.size+1)
    init {
        yLabel = "Probability"
        xLabel = "Value"
        title = if (dataName != null) "CDF Plot for $dataName" else "CDF Plot"
        for(i in xEnd.indices){
            xEnd[i] = v[i+1]
        }
        this.dataMap = mapOf(
            "prob" to cp.asList().dropLast(1),
            "value" to v.asList().dropLast(1),
            "xEnd" to xEnd.asList()
        )
    }

    override fun buildPlot(): Plot {
        var p = ggplot(dataMap)  +
                geomPoint(shape = 16) {
                    x = "value"
                    y = "prob"
                } +
                geomPoint(shape = 1) {
                    x = "xEnd"
                    y = "prob"
                }
//        for (i in v.indices){
//            p = p + geomSegment(yend = 0){
//                x = "value"
//                y = "prob"
//                xend = "value"
//            }
//        }
        p = p + labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}