package ksl.utilities.io.plotting

import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.distributions.InverseCDFIfc
import ksl.utilities.io.KSL
import org.jetbrains.letsPlot.Figure
import org.jetbrains.letsPlot.GGBunch
import java.io.File
import java.nio.file.Path

/**
 * A class for creating and displaying distribution fit plots.
 *
 * FitDistPlot generates a set of four diagnostic plots to assess how well a continuous distribution
 * fits to a given dataset:
 * - Density plot: Compares the empirical density of the data with the PDF of the fitted distribution
 * - QQ plot (Quantile-Quantile): Compares the quantiles of the data with the theoretical quantiles
 * - ECDF plot (Empirical Cumulative Distribution Function): Compares the empirical CDF with the theoretical CDF
 * - PP plot (Probability-Probability): Plots the empirical probabilities against the theoretical probabilities
 *
 * @property defaultPlotDir The default directory where plots will be saved. Defaults to KSL.plotDir.
 * @property title The title of the plot. If dataName is provided in the constructor, the title will include it.
 * @property densityPlot The density plot component comparing data density to the theoretical PDF.
 * @property ecdfPlot The ECDF plot component comparing empirical CDF to the theoretical CDF.
 * @property qqPlot The QQ plot component comparing data quantiles to theoretical quantiles.
 * @property ppPlot The PP plot component comparing empirical probabilities to theoretical probabilities.
 *
 * @param data The data array to be analyzed and plotted.
 * @param cdfFunction The continuous distribution interface providing CDF and PDF functions.
 * @param quantileFun The inverse CDF interface providing quantile functions.
 * @param dataName Optional name for the data being analyzed, used in the plot title.
 */
class FitDistPlot(
    data: DoubleArray,
    private val cdfFunction: ContinuousDistributionIfc,
    private val quantileFun: InverseCDFIfc,
    dataName: String? = null
) {
    var defaultPlotDir: Path = KSL.plotDir
    var title = if (dataName != null) "Fit Distribution Plot for $dataName" else "Fit Distribution Plot"
    val densityPlot: DensityPlot
    val ecdfPlot: ECDFPlot
    val qqPlot: QQPlot = QQPlot(data, quantileFun)
    val ppPlot: PPPlot = PPPlot(data, cdfFunction)

    init {
        densityPlot = DensityPlot(data, cdfFunction::pdf)
        val fn: (Double) -> Double = cdfFunction::cdf
        ecdfPlot = ECDFPlot(data, fn)
    }

    /**
     * Builds a figure containing all four diagnostic plots arranged in a 2x2 grid.
     *
     * The grid layout is:
     * - Top-left: Density plot
     * - Top-right: QQ plot
     * - Bottom-left: ECDF plot
     * - Bottom-right: PP plot
     *
     * @return A Figure object containing the combined plots.
     */
    private fun buildFigure(): Figure {
        val p1 = densityPlot.buildPlot()
        val p2 = qqPlot.buildPlot()
        val p3 = ecdfPlot.buildPlot()
        val p4 = ppPlot.buildPlot()

        val plot = GGBunch()
            .addPlot(p1, 0, 0, 400, 300)
            .addPlot(p2, 400, 0, 400, 300)
            .addPlot(p3, 0, 300, 400, 300)
            .addPlot(p4, 400, 300, 400, 300)
//        val plots = listOf(p1, p2, p3, p4)
//        val regions = listOf(listOf(0, 0, 400, 300), listOf(400, 0, 400, 300),
//            listOf(0, 300, 400, 300), listOf(400, 300, 400, 300))
//        val plot = ggbunch(plots, regions)

// could not get gggrid() to work, something not supported
//        val plots = listOf(p1, p2, p3, p4)
//        return gggrid(plots, ncol = 2) + ggtitle(title) + ggsize(500, 500)
        return plot
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

    fun toHTML() : String {
        return PlotIfc.toHTML(buildFigure())
    }
}