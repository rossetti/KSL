package ksl.utilities.io.plotting

import ksl.observers.welch.WelchDataArrayObserver
import ksl.observers.welch.WelchDataFileAnalyzer
import ksl.utilities.Interval
import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.io.KSL
import ksl.utilities.math.FunctionIfc
import ksl.utilities.statistic.BoxPlotSummary
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.IntegerFrequency
import ksl.utilities.statistic.StateFrequency
import java.awt.Desktop
import java.io.File
import java.nio.file.Path


interface PlotIfc {

    var defaultScale : Int
    var defaultDPI : Int
    var width: Int
    var height: Int
    var title: String?

    fun saveToFile(path: Path, title: String? = null) : File

    fun saveToFile(fileName: String, directory: Path = KSL.outDir, plotTitle: String? = title) : File {
        return saveToFile(directory.resolve(fileName), plotTitle)
    }

    fun showInBrowser(plotTitle: String? = title): File

}

interface PlottingIfc {

    fun scatterPlot(x: DoubleArray, y: DoubleArray, title: String? = null): PlotIfc

    fun boxPlot(boxPlotSummary: BoxPlotSummary, title: String? = null): PlotIfc

    fun multiBoxPlot(list: List<BoxPlotSummary>, title: String? = null): PlotIfc

    fun histogram(histogram: Histogram, title: String? = null, density: Boolean = false): PlotIfc

    fun frequency(frequency: IntegerFrequency, title: String? = null): PlotIfc

    fun frequency(frequency: StateFrequency, title: String? = null): PlotIfc

    fun confidenceIntervals(list: List<Interval>, title: String? = null, referencePoint: Double = Double.NaN): PlotIfc

    fun functionPlot(function: FunctionIfc, interval: Interval, mesh: Double = 0.0): PlotIfc

    fun qqPlot(data: DoubleArray, hypothesized: ContinuousDistributionIfc, title: String? = null): PlotIfc

    fun ppPlot(data: DoubleArray, hypothesized: ContinuousDistributionIfc, title: String? = null): PlotIfc

    fun stateVariablePlot(): PlotIfc

    fun welchPlot(analyzer: WelchDataFileAnalyzer, totalNumObservations: Int): PlotIfc

    fun welchPlot(dataArrayObserver: WelchDataArrayObserver): PlotIfc

    fun welchPlot(averages: DoubleArray, cumAverages: DoubleArray, title: String? = null, responseName: String? = null): PlotIfc

    fun partialSumsPlot(partialSums: DoubleArray, title: String? = null, responseName: String? = null ): PlotIfc
}