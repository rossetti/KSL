package ksl.utilities.io.plotting

import ksl.utilities.io.KSL
import org.jetbrains.letsPlot.core.util.PlotHtmlExport
import org.jetbrains.letsPlot.core.util.PlotHtmlHelper
import org.jetbrains.letsPlot.export.VersionChecker
import org.jetbrains.letsPlot.export.ggsave
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.intern.toSpec
import org.jetbrains.letsPlot.label.ggtitle
import java.awt.Desktop
import java.io.File
import java.io.FileWriter
import java.nio.file.Path

abstract class PlotImp() : PlotIfc {

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

    protected fun openInBrowser(file: File) {
        val desktop = Desktop.getDesktop()
        desktop.browse(file.toURI())
    }

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
        val p = buildPlot()
        val spec = p.toSpec()

        // Export: use PlotHtmlExport utility to generate dynamic HTML (optionally in iframe).
        val html = PlotHtmlExport.buildHtmlFromRawSpecs(
            spec, iFrame = true,
            scriptUrl = PlotHtmlHelper.scriptUrl(VersionChecker.letsPlotJsVersion)
        )
        val fileName = if (title.isEmpty()){
            "tempPlotFile_"
        } else {
            title.replace(" ", "_") + "_"
        }
        return openInBrowser(fileName, html)
    }

    private fun openInBrowser(fileName: String, html: String) : File {
        val file = createTemporaryFile(fileName)
        FileWriter(file).use {
            it.write(html)
        }
        openInBrowser(file)
        return file
    }

    private fun createTemporaryFile(fileName: String): File {
        val tmpDir = File(KSL.plotDir.toString())
        if (!tmpDir.exists()) {
            tmpDir.mkdir()
        }
        return File.createTempFile(fileName, ".html", tmpDir)
    }

    protected abstract fun buildPlot(): Plot
}

internal class ScatterPlot(
    x: DoubleArray,
    y: DoubleArray,
) : PlotImp() {

    private val data: Map<String, DoubleArray>

    init {
        data = mapOf(
            xLabel to x,
            yLabel to y
        )
    }

    override fun buildPlot(): Plot {
        val p = ggplot(data) +
                geomPoint() {
                    x = xLabel
                    y = yLabel
                } +
                ggtitle(title) +
                ggsize(width, height)
        return p
    }
}

