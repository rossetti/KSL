package ksl.utilities.io.plotting

import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs

class ScatterPlot(
    x: DoubleArray,
    y: DoubleArray,
) : BasePlot() {

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