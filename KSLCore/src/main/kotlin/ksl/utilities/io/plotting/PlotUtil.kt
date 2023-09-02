package ksl.utilities.io.plotting

import ksl.observers.welch.WelchDataArrayObserver
import ksl.observers.welch.WelchDataFileAnalyzer
import ksl.utilities.Interval
import ksl.utilities.distributions.CDFIfc
import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.distributions.InverseCDFIfc
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.math.FunctionIfc
import ksl.utilities.statistic.BoxPlotSummary
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.IntegerFrequency
import ksl.utilities.statistic.StateFrequency
import org.jetbrains.letsPlot.core.util.PlotHtmlExport
import org.jetbrains.letsPlot.core.util.PlotHtmlHelper
import org.jetbrains.letsPlot.export.VersionChecker
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.intern.toSpec
import java.io.File

object PlotUtil : PlottingIfc {

    override fun scatterPlot(x: DoubleArray, y: DoubleArray, title: String): PlotIfc {
        return ScatterPlot(x, y).apply { this.title = title }
    }

    override fun boxPlot(boxPlotSummary: BoxPlotSummary, title: String): PlotIfc {
        return BoxPlot(boxPlotSummary).apply { this.title = title }
    }

    override fun multiBoxPlot(map: Map<String, BoxPlotSummary>, title: String): PlotIfc {
        return MultiBoxPlot(map).apply { this.title = title }
    }

    override fun histogram(histogram: Histogram, proportions: Boolean, title: String): PlotIfc {
        return HistogramPlot(histogram, proportions).apply { this.title = title }
    }

    override fun frequency(frequency: IntegerFrequency, proportions: Boolean, title: String): PlotIfc {
        return IntegerFrequencyPlot(frequency, proportions).apply { this.title = title }
    }

    override fun frequency(frequency: StateFrequency, proportions: Boolean, title: String): PlotIfc {
        return StateFrequencyPlot(frequency, proportions).apply { this.title = title }
    }

    override fun confidenceIntervals(map: Map<String, Interval>, title: String, referencePoint: Double?): PlotIfc {
        return ConfidenceIntervalsPlot(map, referencePoint).apply { this.title = title }
    }

    override fun functionPlot(fn: ((Double) -> Double), interval: Interval, numPoints: Int, title: String): PlotIfc {
        return FunctionPlot(fn, interval, numPoints).apply { this.title = title }
    }

    override fun qqPlot(data: DoubleArray, quantileFunction: InverseCDFIfc, title: String): PlotIfc {
        return QQPlot(data, quantileFunction).apply { this.title = title }
    }

    override fun ppPlot(data: DoubleArray, cdfIfc: CDFIfc, title: String): PlotIfc {
        return PPPlot(data, cdfIfc).apply { this.title = title }
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
        title: String,
        responseName: String?
    ): PlotIfc {
        TODO("Not yet implemented")
    }

    override fun partialSumsPlot(partialSums: DoubleArray, title: String, responseName: String?): PlotIfc {
        TODO("Not yet implemented")
    }
}