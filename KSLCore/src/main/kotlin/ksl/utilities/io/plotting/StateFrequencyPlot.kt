package ksl.utilities.io.plotting

import ksl.utilities.statistic.StateFrequency
import org.jetbrains.letsPlot.Stat
import org.jetbrains.letsPlot.geom.geomBar
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs

class StateFrequencyPlot(private val frequency: StateFrequency, proportions: Boolean = false) : BasePlot() {

    private val data: MutableMap<String, List<Any>> = mutableMapOf()
    private var dataType: String

    init {
        data["states"] = frequency.stateNames
        if (proportions) {
            dataType = "proportions"
            data[dataType] = frequency.proportions.asList()
        } else {
            dataType = "counts"
            data[dataType] = frequency.frequencies.asList()
        }
        xLabel = "states"
        yLabel = dataType
    }

    override fun buildPlot(): Plot {
        val p = ggplot(data) +
                geomBar(stat = Stat.identity) {
                    x = "states"
                    y = dataType
                } +
                labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}