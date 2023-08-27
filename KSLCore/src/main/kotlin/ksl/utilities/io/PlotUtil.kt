package ksl.utilities.io

import ksl.observers.welch.WelchDataArrayObserver
import ksl.observers.welch.WelchDataFileAnalyzer
import ksl.utilities.Interval
import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.math.FunctionIfc
import ksl.utilities.statistic.BoxPlotSummary
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.IntegerFrequency
import ksl.utilities.statistic.StateFrequency

object PlotUtil: PlottingIfc {
    override fun scatterPlot(x: DoubleArray, y: DoubleArray, title: String?): PlotIfc {
        TODO("Not yet implemented")
    }

    override fun boxPlot(boxPlotSummary: BoxPlotSummary, title: String?): PlotIfc {
        TODO("Not yet implemented")
    }

    override fun multiBoxPlot(list: List<BoxPlotSummary>, title: String?): PlotIfc {
        TODO("Not yet implemented")
    }

    override fun histogram(histogram: Histogram, title: String?, density: Boolean): PlotIfc {
        TODO("Not yet implemented")
    }

    override fun frequency(frequency: IntegerFrequency, title: String?): PlotIfc {
        TODO("Not yet implemented")
    }

    override fun frequency(frequency: StateFrequency, title: String?): PlotIfc {
        TODO("Not yet implemented")
    }

    override fun confidenceIntervals(list: List<Interval>, title: String?, referencePoint: Double): PlotIfc {
        TODO("Not yet implemented")
    }

    override fun functionPlot(function: FunctionIfc, interval: Interval, mesh: Double): PlotIfc {
        TODO("Not yet implemented")
    }

    override fun qqPlot(data: DoubleArray, hypothesized: ContinuousDistributionIfc, title: String?): PlotIfc {
        TODO("Not yet implemented")
    }

    override fun ppPlot(data: DoubleArray, hypothesized: ContinuousDistributionIfc, title: String?): PlotIfc {
        TODO("Not yet implemented")
    }

    override fun stateVariablePlot(): PlotIfc {
        TODO("Not yet implemented")
    }

    override fun welchPlot(analyzer: WelchDataFileAnalyzer, totalNumObservations: Int): PlotIfc {
        TODO("Not yet implemented")
    }

    override fun welchPlot(dataArrayObserver: WelchDataArrayObserver): PlotIfc {
        TODO("Not yet implemented")
    }

    override fun welchPlot(
        averages: DoubleArray,
        cumAverages: DoubleArray,
        title: String?,
        responseName: String?
    ): PlotIfc {
        TODO("Not yet implemented")
    }

    override fun partialSumsPlot(partialSums: DoubleArray, title: String?, responseName: String?): PlotIfc {
        TODO("Not yet implemented")
    }
}