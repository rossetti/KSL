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

    /**
     *  Constructs a plot that has the times and values for the provided replication [repNum]
     *  up to and including the [time]. The default value of time is Double.MAX_VALUE,
     *  which will result in all values for the replication.
     */
    constructor(responseTrace: ResponseTrace, repNum: Int, time: Double = Double.MAX_VALUE) : this(
        responseTrace.traceDataMap(repNum.toDouble(), time),
        responseTrace.name
    )

    /**
     *  The data map is assumed to have keys "times" and "values"
     *  Element "times" holds the times that the variable changed
     *  Element "values" holds the values associated with each time change.
     */
    constructor(dataMap: Map<String, DoubleArray>, responseName: String) : this(
        dataMap["values"]!!, dataMap["times"]!!, responseName
    )

    init {
        xLabel = "t"
        yLabel = "y(t)"
        title = "Sample path for $responseName"
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