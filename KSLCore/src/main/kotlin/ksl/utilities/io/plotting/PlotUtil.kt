package ksl.utilities.io.plotting

import ksl.observers.welch.WelchDataArrayObserver
import ksl.observers.welch.WelchDataFileAnalyzer
import ksl.utilities.Interval
import ksl.utilities.distributions.ContinuousDistributionIfc
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

object PlotUtil: PlottingIfc {

    /**
     *  Shows a lets-plot plot in a browser window
     */
    fun showPlotInBrowser(plot: Plot, tmpFileName: String? = null) : File {
        val spec = plot.toSpec()

        // Export: use PlotHtmlExport utility to generate dynamic HTML (optionally in iframe).
        val html = PlotHtmlExport.buildHtmlFromRawSpecs(
            spec, iFrame = true,
            scriptUrl = PlotHtmlHelper.scriptUrl(VersionChecker.letsPlotJsVersion)
        )
        val fileName = if (tmpFileName == null) {
            "tempPlotFile_"
        } else {
            tmpFileName.replace(" ", "_") + "_"
        }
        return KSLFileUtil.openInBrowser(fileName, html)
    }
    
    override fun scatterPlot(x: DoubleArray, y: DoubleArray, title: String): PlotIfc {
        TODO("Not yet implemented")
    }

    override fun boxPlot(boxPlotSummary: BoxPlotSummary, title: String): PlotIfc {
        TODO("Not yet implemented")
    }

    override fun multiBoxPlot(map: Map<String, BoxPlotSummary>, title: String): PlotIfc {
        TODO("Not yet implemented")
    }

    override fun histogram(histogram: Histogram, title: String, density: Boolean): PlotIfc {
        TODO("Not yet implemented")
    }

    override fun frequency(frequency: IntegerFrequency, title: String): PlotIfc {
        TODO("Not yet implemented")
    }

    override fun frequency(frequency: StateFrequency, title: String): PlotIfc {
        TODO("Not yet implemented")
    }

    override fun confidenceIntervals(map: Map<String, Interval>, title: String, referencePoint: Double?): PlotIfc {
        TODO("Not yet implemented")
    }

    override fun functionPlot(function: FunctionIfc, interval: Interval, mesh: Double): PlotIfc {
        TODO("Not yet implemented")
    }

    override fun qqPlot(data: DoubleArray, hypothesized: ContinuousDistributionIfc, title: String): PlotIfc {
        TODO("Not yet implemented")
    }

    override fun ppPlot(data: DoubleArray, hypothesized: ContinuousDistributionIfc, title: String): PlotIfc {
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
        title: String,
        responseName: String?
    ): PlotIfc {
        TODO("Not yet implemented")
    }

    override fun partialSumsPlot(partialSums: DoubleArray, title: String, responseName: String?): PlotIfc {
        TODO("Not yet implemented")
    }
}