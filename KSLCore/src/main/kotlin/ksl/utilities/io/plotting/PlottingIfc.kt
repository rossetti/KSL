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
    var title: String
    var xLabel: String
    var yLabel: String

    fun saveToFile(path: Path, plotTitle: String = title) : File

    fun saveToFile(fileName: String, directory: Path = KSL.outDir, plotTitle: String = title) : File {
        return saveToFile(directory.resolve(fileName), plotTitle)
    }

    fun showInBrowser(plotTitle: String = title): File

}

interface PlottingIfc {

    fun scatterPlot(x: DoubleArray, y: DoubleArray, title: String = ""): PlotIfc

    fun boxPlot(boxPlotSummary: BoxPlotSummary, title: String = ""): PlotIfc

    fun multiBoxPlot(list: List<BoxPlotSummary>, title: String = ""): PlotIfc

    fun histogram(histogram: Histogram, title: String = "", density: Boolean = false): PlotIfc

    fun frequency(frequency: IntegerFrequency, title: String = ""): PlotIfc

    fun frequency(frequency: StateFrequency, title: String = ""): PlotIfc

    fun confidenceIntervals(list: List<Interval>, title: String = "", referencePoint: Double = Double.NaN): PlotIfc

    fun functionPlot(function: FunctionIfc, interval: Interval, mesh: Double = 0.0): PlotIfc

    fun qqPlot(data: DoubleArray, hypothesized: ContinuousDistributionIfc, title: String = ""): PlotIfc

    fun ppPlot(data: DoubleArray, hypothesized: ContinuousDistributionIfc, title: String = ""): PlotIfc

    fun stateVariablePlot(): PlotIfc

    fun welchPlot(analyzer: WelchDataFileAnalyzer, totalNumObservations: Int): PlotIfc

    fun welchPlot(dataArrayObserver: WelchDataArrayObserver): PlotIfc

    fun welchPlot(averages: DoubleArray, cumAverages: DoubleArray, title: String = "", responseName: String? = null): PlotIfc

    fun partialSumsPlot(partialSums: DoubleArray, title: String = "", responseName: String? = null ): PlotIfc
}