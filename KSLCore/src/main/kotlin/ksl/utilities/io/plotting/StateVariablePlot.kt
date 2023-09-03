package ksl.utilities.io.plotting

import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.geom.geomStep
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs

class StateVariablePlot(
    values: DoubleArray,
    times: DoubleArray,
    val responseName: String
) : BasePlot() {
    private val data: Map<String, DoubleArray>

    init {
        xLabel = "t"
        yLabel = "y(t)"
        title = "Sample path for y(t)"
        data = mapOf(
            "times" to times,
            "values" to values
        )
    }

    override fun buildPlot(): Plot {
        val p = ggplot(data) +
                geomStep() {
                    x = "times"
                    y = "values"
                } +
                geomPoint(color = "red") {
                    x = "times"
                    y = "values"
                } +
                labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}