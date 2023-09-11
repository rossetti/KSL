package ksl.utilities.io.plotting

import ksl.utilities.Interval
import ksl.utilities.distributions.CDFIfc
import ksl.utilities.statistic.Statistic
import org.jetbrains.letsPlot.geom.geomFunction
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs

class CDFDiffPlot(
    private val data: DoubleArray,
    private val cdf: CDFIfc,
    numPoints: Int = 512
) : BasePlot() {

    private val min = data.min()
    private val max = data.max()

    init{
        title = "Distribution-Function-Differences Plot"
        yLabel = "F(x) - Empirical F(x)"
    }

    var numPoints: Int = numPoints
        set(value) {
            require(value > 0) { "The number of points on the x-axis must be > 0" }
            field = value
        }

    private fun diffFun(x: Double): Double {
        return cdf.cdf(x) - Statistic.empiricalCDF(data , x)
    }

    override fun buildPlot(): Plot {
        val limits = Pair(min, max)
        val p = ggplot(null) +
                geomFunction(xlim = limits, fn = ::diffFun, n = numPoints) +
                labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}