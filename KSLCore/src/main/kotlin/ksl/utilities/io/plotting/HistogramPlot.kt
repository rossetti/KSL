/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
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