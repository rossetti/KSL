package ksl.utilities.io.plotting

import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.HistogramIfc
import org.jetbrains.letsPlot.geom.geomFunction
import org.jetbrains.letsPlot.geom.geomRect
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.ggtitle
import org.jetbrains.letsPlot.label.ylab
import org.jetbrains.letsPlot.tooltips.layerTooltips

//TODO the height scaling is not right for the density overlay
class HistogramPlot(
    private val histogram: HistogramIfc,
    var proportions: Boolean = false
) : BasePlot() {

    private val data: Map<String, DoubleArray>
    private val lowerLimits: DoubleArray
    private val upperLimits: DoubleArray

    init {
        upperLimits = histogram.upperLimits
        if (upperLimits.last().isInfinite()) {
            upperLimits[upperLimits.lastIndex] = histogram.max + 1.0
        }
        lowerLimits = histogram.lowerLimits
        if (lowerLimits.first().isInfinite()) {
            lowerLimits[0] = histogram.min - 1
        }
        data = if (proportions) {
            yLabel = "Bin Proportions"
            mapOf(
                "xmin" to lowerLimits,
                "xmax" to upperLimits,
                "ymax" to histogram.binFractions
            )
        } else {
            yLabel = "Bin Counts"
            mapOf<String, DoubleArray>(
                "xmin" to lowerLimits,
                "xmax" to upperLimits,
                "ymax" to histogram.binCounts
            )
        }
    }

    constructor(data: DoubleArray, proportions: Boolean = false) :
            this(Histogram.create(data), proportions)

    var density: ((Double) -> Double)? = null
    var numPoints: Int = 512
        set(value) {
            require(value > 0) { "The number of points on the x-axis must be > 0" }
            field = value
        }

    override fun buildPlot(): Plot {
        var p = ggplot() +
                geomRect(
                    data, ymin = 0.0,
                    tooltips = layerTooltips()
                        .format("xmin", ".1f")
                        .format("xmax", ".1f")
                        .line("@ymax")
                        .line("[@xmin, @xmax]")
                ) {
                    xmin = "xmin"
                    xmax = "xmax"
                    ymax = "ymax"
                }

        if (density != null) {
            val limits = Pair(lowerLimits[0], upperLimits[upperLimits.lastIndex])
            p = p + geomFunction(xlim = limits, fn = density, n = numPoints, color = "red")
        }
        p = p + ylab(yLabel) +
                ggtitle(title) +
                ggsize(width, height)
        return p
    }

}