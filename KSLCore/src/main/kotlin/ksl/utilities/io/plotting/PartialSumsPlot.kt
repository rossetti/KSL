package ksl.utilities.io.plotting

import org.jetbrains.kotlinx.dataframe.impl.asList
import org.jetbrains.letsPlot.geom.geomLine
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs

class PartialSumsPlot(partialSums: DoubleArray, dataName: String? = null) : BasePlot() {

    private val data: Map<String, List<Number>>

    init {
        yLabel = "Partial Sums"
        xLabel = "Indices"
        title = if (dataName != null) "Partial Sums Plot for $dataName" else "Partial Sums Plot"
        data = mapOf(
            "indices" to (1..partialSums.size).asList(),
            "partial sums" to partialSums.asList()
        )
    }

    override fun buildPlot(): Plot {
        val p = ggplot(data) +
                geomLine() {
                    x = "indices"
                    y = "partial sums"
                } +
                labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}