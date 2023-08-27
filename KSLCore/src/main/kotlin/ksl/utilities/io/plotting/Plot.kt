package ksl.utilities.io.plotting

import java.awt.Desktop
import java.io.File
import java.nio.file.Path

abstract class Plot(override var title: String? = null) : PlotIfc {

    override var defaultScale: Int = 1
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

    protected fun openInBrowser(file: File) {
        val desktop = Desktop.getDesktop()
        desktop.browse(file.toURI())
    }

    override fun saveToFile(path: Path, plotTitle: String?): File {
        TODO("Not yet implemented")
    }

    override fun showInBrowser(plotTitle: String?): File {
        TODO("Not yet implemented")
    }

}

