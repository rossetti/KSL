package ksl.utilities.io.plotting

import ksl.observers.welch.WelchDataArrayObserver
import ksl.observers.welch.WelchDataFileAnalyzer
import ksl.utilities.statistic.Statistic
import org.jetbrains.kotlinx.dataframe.impl.asList
import org.jetbrains.letsPlot.geom.geomHLine
import org.jetbrains.letsPlot.geom.geomLine
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs

class PartialSumsPlot @JvmOverloads constructor(partialSums: DoubleArray, dataName: String? = null) : BasePlot() {

    private val data: Map<String, List<Number>>

    constructor(welchDataFileAnalyzer: WelchDataFileAnalyzer) : this(
        Statistic.partialSums(welchDataFileAnalyzer.batchWelchAverages()),
        welchDataFileAnalyzer.responseName
    )

    constructor(welchData: WelchDataArrayObserver) : this(welchData.welchAverages, welchData.responseName)

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
                geomHLine(yintercept = 0.0, color = "red", linetype = "dashed") +
                labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}