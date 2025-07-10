package ksl.utilities.io.plotting

import ksl.utilities.Interval
import org.jetbrains.letsPlot.geom.geomFunction
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs

class FunctionPlot @JvmOverloads constructor(
    private val function: ((Double) -> Double),
    private val interval: Interval,
    numPoints: Int = 512
) : BasePlot() {

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