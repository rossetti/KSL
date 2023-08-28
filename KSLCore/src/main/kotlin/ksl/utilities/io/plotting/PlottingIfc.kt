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

    enum class ExtType {
        PNG, JPEG, HTML, TIF, SVG
    }

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

    fun welchPlot(
        averages: DoubleArray,
        cumAverages: DoubleArray,
        title: String = "",
        responseName: String? = null
    ): PlotIfc

    fun partialSumsPlot(partialSums: DoubleArray, title: String = "", responseName: String? = null): PlotIfc
}