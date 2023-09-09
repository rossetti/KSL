package ksl.utilities.io.plotting

import ksl.observers.ResponseTrace
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

   constructor(responseTrace: ResponseTrace, repNum: Double, time: Double = Double.MAX_VALUE): this(
       responseTrace.traceValues(repNum, time),
       responseTrace.traceTimes(repNum, time),
       responseTrace.name
   )
    
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