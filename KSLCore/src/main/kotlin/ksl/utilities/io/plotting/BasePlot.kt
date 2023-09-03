@file:Suppress("DuplicatedCode")

package ksl.utilities.io.plotting

import ksl.utilities.io.KSL
import ksl.utilities.io.KSLFileUtil
import org.jetbrains.letsPlot.core.util.PlotHtmlExport
import org.jetbrains.letsPlot.core.util.PlotHtmlHelper
import org.jetbrains.letsPlot.export.VersionChecker
import org.jetbrains.letsPlot.export.ggsave
import org.jetbrains.letsPlot.intern.toSpec
import java.io.File
import java.nio.file.Path

abstract class BasePlot() : PlotIfc {

    override var defaultPlotDir: Path = KSL.plotDir

    override var defaultScale: Int = 2
        set(value) {
            require(value > 0) { "The scale must be > 0" }
            field = value
        }
    override var defaultDPI: Int = 144
        set(value) {
            require(value > 0) { "The DPI must be > 0" }
            field = value
        }
    override var width: Int = 500
        set(value) {
            require(value > 0) { "The width must be > 0" }
            field = value
        }
    override var height: Int = 350
        set(value) {
            require(value > 0) { "The height must be > 0" }
            field = value
        }

    override var title: String = ""
    override var xLabel: String = "x"
    override var yLabel: String = "y"

    override fun saveToFile(
        fileName: String,
        directory: Path,
        plotTitle: String,
        extType: PlotIfc.ExtType
    ): File {
        title = plotTitle
        val plot = buildPlot()
        val fn = fileName + "." + extType.name
        val pn = ggsave(plot, fn, defaultScale, defaultDPI, directory.toString())
        return File(pn)
    }

    override fun showInBrowser(plotTitle: String): File {
        title = plotTitle
        val plot = buildPlot()
        val spec = plot.toSpec()
        // Export: use PlotHtmlExport utility to generate dynamic HTML (optionally in iframe).
        val html = PlotHtmlExport.buildHtmlFromRawSpecs(
            spec, iFrame = true,
            scriptUrl = PlotHtmlHelper.scriptUrl(VersionChecker.letsPlotJsVersion)
        )
        val fileName = if (title.isEmpty()) {
            "tempPlotFile_"
        } else {
            title.replace(" ", "_") + "_"
        }
        return KSLFileUtil.openInBrowser(fileName, html, defaultPlotDir)
    }

    override fun toString(): String {
        return "PlotImp(" +
                "defaultScale=$defaultScale, " +
                "defaultDPI=$defaultDPI, " +
                "width=$width, " +
                "height=$height, " +
                "title='$title', " +
                "xLabel='$xLabel', " +
                "yLabel='$yLabel'" +
                ")"
    }


}

