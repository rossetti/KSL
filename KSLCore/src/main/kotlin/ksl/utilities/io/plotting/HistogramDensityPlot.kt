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

import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.Statistic
import org.jetbrains.letsPlot.geom.geomDensity
import org.jetbrains.letsPlot.geom.geomHistogram
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.ggtitle
import org.jetbrains.letsPlot.label.ylab

class HistogramDensityPlot @JvmOverloads constructor(data: DoubleArray, binWidth: Double? = null) : BasePlot() {

    private val myData: Map<String, DoubleArray> = mapOf(
        "data" to data
    )

    private val myBinWidth: Double

    var densityFillColor: Int = 0xFF6666
    var densityAlpha : Double = 0.2

    init {
        title = "Histogram with Density Plot"
        yLabel = "Density"
        myBinWidth = if ((binWidth != null) && (binWidth > 0.0)) {
            binWidth
        } else {
            val stat = Statistic(data)
            Histogram.recommendBinWidth(stat.count, stat.standardDeviation)
        }
    }

    override fun buildPlot(): Plot {
 //       println("bin width: $myBinWidth")
        val p = ggplot(myData){x = "data"} +
                geomHistogram(binWidth = myBinWidth, fill = "paper") {
                    y = "..density.."
                } +
                geomDensity(alpha = densityAlpha, fill = densityFillColor){x = "data"} + ylab(yLabel) +
                ggtitle(title) + ggsize(width, height)
        return p
    }

}

fun main() {
    val data = NormalRV().sample(100)
    val plot = HistogramDensityPlot(data)
    plot.showInBrowser()
}