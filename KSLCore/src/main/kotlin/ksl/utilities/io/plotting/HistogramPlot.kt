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

class HistogramPlot(
    private val histogram: HistogramIfc,
    var proportions: Boolean = false
) : BasePlot() {

    private val data: Map<String, DoubleArray>
    private val lowerLimits: DoubleArray
    private val upperLimits: DoubleArray

    init {
        title = "Histogram Plot"
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

    override fun buildPlot(): Plot {
        val p = ggplot() +
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
                } + ylab(yLabel) +
                ggtitle(title) +
                ggsize(width, height)
        return p
    }

}