package ksl.utilities.io.plotting

import ksl.observers.welch.WelchDataArrayObserver
import ksl.observers.welch.WelchDataFileAnalyzer
import ksl.utilities.statistics
import ksl.utilities.toDoubles
import org.jetbrains.letsPlot.geom.geomLine
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs

class WelchPlot(avgs: DoubleArray, cumAvgs: DoubleArray, val responseName: String) : BasePlot() {

    private val data: Map<String, DoubleArray>

    init {
        xLabel = "Observation Number"
        yLabel = responseName
        title = "Welch Plot for $responseName"
        data = mapOf(
            "Observation Number" to (1..avgs.size).toList().toIntArray().toDoubles(),
            "Welch Average" to avgs,
            "Cumulative Average" to cumAvgs
        )
    }

    constructor(dataArrayObserver: WelchDataArrayObserver) : this(
        avgs = dataArrayObserver.welchAverages,
        cumAvgs = dataArrayObserver.welchCumulativeAverages,
        responseName = dataArrayObserver.responseName
    ) {
        val deltaT = dataArrayObserver.avgTimeBtwObservationsForEachReplication.statistics().average
        val ts = "%.2f".format(deltaT)
        title = "$title, 1 obs = $ts time units"
    }

    constructor(
        analyzer: WelchDataFileAnalyzer,
        totalNumObservations: Int = analyzer.minNumObservationsInReplications.toInt()
    ) : this(
        avgs = analyzer.welchAveragesNE(totalNumObservations),
        cumAvgs = analyzer.cumulativeWelchAverages(totalNumObservations),
        responseName = analyzer.responseName
    ) {
        val deltaT = analyzer.averageTimePerObservation
        val ts = "%.2f".format(deltaT)
        title = "$title, 1 obs = $ts time units"
    }

    override fun buildPlot(): Plot {
        val p = ggplot(data) +
                geomLine() {
                    x = "Observation Number"
                    y = "Welch Average"
                } +
                geomLine(color = "red") {
                    x = "Observation Number"
                    y = "Cumulative Average"
                } +
                labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}