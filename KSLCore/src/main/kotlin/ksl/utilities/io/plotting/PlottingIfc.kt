package ksl.utilities.io.plotting

import ksl.observers.welch.WelchDataArrayObserver
import ksl.observers.welch.WelchDataFileAnalyzer
import ksl.utilities.Interval
import ksl.utilities.distributions.CDFIfc
import ksl.utilities.distributions.InverseCDFIfc
import ksl.utilities.statistic.*


interface PlottingIfc {

    fun scatterPlot(x: DoubleArray, y: DoubleArray, title: String = ""): PlotIfc

    fun boxPlot(boxPlotSummary: BoxPlotSummary, title: String = ""): PlotIfc

    fun multiBoxPlot(map: Map<String, BoxPlotSummary>, title: String = ""): PlotIfc

    fun histogram(histogram: Histogram, proportions: Boolean = false, title: String = ""): PlotIfc

    fun frequency(frequency: IntegerFrequency, proportions: Boolean = false, title: String = ""): PlotIfc

    fun frequency(frequency: StateFrequency, proportions: Boolean = false, title: String = ""): PlotIfc

    fun confidenceIntervals(map: Map<String, Interval>, title: String = "", referencePoint: Double? = null): PlotIfc

    fun confidenceIntervals(list: List<StatisticIfc>, level: Double = 0.95, title: String = "", referencePoint: Double? = null): PlotIfc {
        val m = mutableMapOf<String, Interval>()
        for( s in list){
            m[s.name] = s.confidenceInterval(level)
        }
        return confidenceIntervals(m, title, referencePoint)
    }

    fun functionPlot(fn: ((Double) -> Double), interval: Interval, numPoints: Int = 512, title: String = ""): PlotIfc

    fun qqPlot(data: DoubleArray, quantileFunction: InverseCDFIfc, title: String = ""): PlotIfc

    fun ppPlot(data: DoubleArray, cdfIfc: CDFIfc, title: String = ""): PlotIfc

    fun stateVariablePlot(): PlotIfc

    fun welchPlot(analyzer: WelchDataFileAnalyzer, totalNumObservations: Int): PlotIfc

    fun welchPlot(dataArrayObserver: WelchDataArrayObserver): PlotIfc

    fun welchPlot(
        averages: DoubleArray,
        cumAverages: DoubleArray,
        title: String = "",
        responseName: String? = null
    ): PlotIfc

    fun partialSumsPlot(partialSums: DoubleArray, title: String = "", responseName: String? = null): PlotIfc
}