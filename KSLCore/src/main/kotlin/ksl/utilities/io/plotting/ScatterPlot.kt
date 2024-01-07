package ksl.utilities.io.plotting

import org.jetbrains.letsPlot.geom.geomHLine
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.geom.geomVLine
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs

class ScatterPlot(
    x: DoubleArray,
    y: DoubleArray,
    private val horizontalReference: Double? = null,
    private val verticalReference: Double? = null
) : BasePlot() {

    private val data: Map<String, DoubleArray>

    init {
        data = mapOf(
            "xData" to x,
            "yData" to y
        )
    }

    override fun buildPlot(): Plot {
        var p = ggplot(data) +
                geomPoint() {
                    x = "xData"
                    y = "yData"
                } +
                labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        if (horizontalReference != null){
            p += geomHLine(yintercept = horizontalReference, color = "red", linetype = "dashed")
        }
        if (verticalReference != null){
            p += geomVLine(xintercept = verticalReference, color = "blue", linetype = "dashed")
        }
        return p
    }
}