package ksl.utilities.io.plotting

import ksl.observers.welch.WelchDataArrayObserver
import ksl.observers.welch.WelchDataFileAnalyzer
import ksl.utilities.Interval
import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.io.KSL
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.math.FunctionIfc
import ksl.utilities.statistic.*
import org.jetbrains.letsPlot.core.util.PlotHtmlExport
import org.jetbrains.letsPlot.core.util.PlotHtmlHelper
import org.jetbrains.letsPlot.export.VersionChecker
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.intern.toSpec
import java.io.File
import java.nio.file.Path


interface PlotIfc {

    enum class ExtType {
        PNG, JPEG, HTML, TIF, SVG
    }

    /**
     *  A lets-plot representation of the plot. Allows for
     *  customization of the plot.
     */
    val plot: Plot

    /** the scale associated with the plot **/
    var defaultScale: Int

    /**
     *  the dots per inch for the plot
     */
    var defaultDPI: Int

    /**
     * The width of the container holding the plot
     */
    var width: Int

    /**
     *  The height of the container holding the plot
     */
    var height: Int

    /**
     *  The title of the plot
     */
    var title: String

    /**
     *  the label for the x-axis
     */
    var xLabel: String

    /**
     *  the label for the y-axis
     */
    var yLabel: String

    /**
     * @param fileName the name of the file without an extension
     * @param directory the path to the directory to contain the file
     * @param plotTitle the title of the plot if different from title property
     * @param extType the type of file, defaults to PNG
     * @return a File reference to the created file
     */
    fun saveToFile(
        fileName: String,
        directory: Path = KSL.plotDir,
        plotTitle: String = title,
        extType: ExtType = ExtType.PNG
    ): File

    /** Opens up a browser window and shows the contents of the plot within
     *  the browser.  A temporary file is created to represent the plot for display
     *  within the browser.
     *
     * @param plotTitle the title of the plot if different from the title property
     * @return a File reference to the created file
     */
    fun showInBrowser(plotTitle: String = title): File

}

interface PlottingIfc {

    fun scatterPlot(x: DoubleArray, y: DoubleArray, title: String = ""): PlotIfc

    fun boxPlot(boxPlotSummary: BoxPlotSummary, title: String = ""): PlotIfc

    fun multiBoxPlot(map: Map<String, BoxPlotSummary>, title: String = ""): PlotIfc

    fun histogram(histogram: Histogram, title: String = "", density: Boolean = false): PlotIfc

    fun frequency(frequency: IntegerFrequency, title: String = ""): PlotIfc

    fun frequency(frequency: StateFrequency, title: String = ""): PlotIfc

    fun confidenceIntervals(map: Map<String, Interval>, title: String = "", referencePoint: Double? = null): PlotIfc

    fun confidenceIntervals(list: List<StatisticIfc>, level: Double = 0.95, title: String = "", referencePoint: Double? = null): PlotIfc {
        val m = mutableMapOf<String, Interval>()
        for( s in list){
            m[s.name] = s.confidenceInterval(level)
        }
        return confidenceIntervals(m, title, referencePoint)
    }

    fun functionPlot(function: FunctionIfc, interval: Interval, mesh: Double = 0.0): PlotIfc

    fun qqPlot(data: DoubleArray, hypothesized: ContinuousDistributionIfc, title: String = ""): PlotIfc

    fun ppPlot(data: DoubleArray, hypothesized: ContinuousDistributionIfc, title: String = ""): PlotIfc

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