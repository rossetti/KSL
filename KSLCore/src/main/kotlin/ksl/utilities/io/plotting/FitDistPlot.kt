package ksl.utilities.io.plotting

import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.distributions.InverseCDFIfc
import ksl.utilities.io.KSL
import org.jetbrains.letsPlot.Figure
import org.jetbrains.letsPlot.GGBunch
import java.io.File
import java.nio.file.Path

class FitDistPlot(
    data: DoubleArray,
    private val cdfFunction: ContinuousDistributionIfc,
    private val quantileFun: InverseCDFIfc,
    dataName: String? = null
) {
    var defaultPlotDir: Path = KSL.plotDir
    var title = if (dataName != null) "Fit Distribution Plot for $dataName" else "Fit Distribution Plot"
    val histogramPlot: HistogramPlot
    val ecdfPlot: ECDFPlot
    val qqPlot: QQPlot = QQPlot(data, quantileFun)
    val ppPlot: PPPlot = PPPlot(data, cdfFunction)

    init {
        histogramPlot = HistogramPlot(data, proportions = true)
        histogramPlot.density = cdfFunction::pdf
        val fn: (Double) -> Double = cdfFunction::cdf
        ecdfPlot = ECDFPlot(data, fn)
    }

    private fun buildFigure(): Figure {
        val p1 = histogramPlot.buildPlot()
        val p2 = qqPlot.buildPlot()
        val p3 = ecdfPlot.buildPlot()
        val p4 = ppPlot.buildPlot()

        val p = GGBunch()
            .addPlot(p1, 0, 0, 400, 300)
            .addPlot(p2, 400, 0, 400, 300)
            .addPlot(p3, 0, 300, 400, 300)
            .addPlot(p4, 400, 300, 400, 300)
// could not get gggrid() to work, something not supported
//        val plots = listOf(p1, p2, p3, p4)
//        return gggrid(plots, ncol = 2) + ggtitle(title) + ggsize(500, 500)
        return p
    }

    /**
     * @param fileName the name of the file without an extension
     * @param directory the path to the directory to contain the file
     * @param extType the type of file, defaults to PNG
     * @return a File reference to the created file
     */
    fun saveToFile(
        fileName: String,
        directory: Path = defaultPlotDir,
        extType: PlotIfc.ExtType = PlotIfc.ExtType.PNG
    ): File {
        val figure = buildFigure()
        return PlotIfc.saveToFile(figure, fileName, directory, extType)
    }

    /** Opens up a browser window and shows the contents of the plot within
     *  the browser.  A temporary file is created to represent the plot for display
     *  within the browser.
     *
     * @param plotTitle the title of the plot if different from the title property
     * @return a File reference to the created file
     */
    fun showInBrowser(plotTitle: String = title): File{
        val figure = buildFigure()
        val fileName = if (plotTitle.isEmpty()) {
            "tempPlotFile_"
        } else {
            plotTitle.replace(" ", "_") + "_"
        }
        return PlotIfc.showPlotInBrowser(figure, fileName, defaultPlotDir)
    }
}