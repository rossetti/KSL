/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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

import ksl.observers.ResponseTrace
import org.jetbrains.letsPlot.geom.geomLine
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs

/**
 * Plots multiple observation sequences for an observation-based response
 * variable on a single set of axes.
 *
 * Each entry in [seriesDataMap] becomes one coloured line-and-point series.
 * The key is the series label shown in the legend (e.g. `"Rep 1"`); the
 * value is the array of per-replication observations in collection order.
 * Series may have different lengths; each series is indexed independently
 * starting at 1.
 *
 * The convenience constructor accepts a [ResponseTrace] directly and handles
 * data extraction and label generation automatically.
 *
 * @param seriesDataMap  series label → per-replication observations
 * @param responseName   name used in the plot title
 */
class MultiSeriesObservationsPlot(
    seriesDataMap: Map<String, DoubleArray>,
    val responseName: String
) : BasePlot() {

    private val myData: Map<String, List<Any>>

    /**
     * Constructs a multi-series observations plot directly from a [ResponseTrace].
     *
     * Observation values for each replication in [repNums] are retrieved over
     * the time window [[startTime], [endTime]]. Each series is labelled
     * `"Rep N"` where N is the replication number.
     *
     * The default for [repNums] is [ResponseTrace.replicationNumbers] (all
     * recorded replications). For large traces, pass an explicit subset to
     * limit how much data is loaded.
     *
     * @param responseTrace the trace to read from
     * @param repNums       replication numbers to plot; defaults to all in the trace
     * @param startTime     lower bound of the time window; defaults to 0.0
     * @param endTime       upper bound of the time window; defaults to [Double.MAX_VALUE]
     */
    constructor(
        responseTrace: ResponseTrace,
        repNums: List<Int> = responseTrace.replicationNumbers,
        startTime: Double = 0.0,
        endTime: Double = Double.MAX_VALUE
    ) : this(
        seriesDataMap = responseTrace.traceDataMaps(repNums, startTime, endTime)
            .mapKeys  { (repNum, _)  -> "Rep $repNum" }
            .mapValues { (_, dataMap) -> dataMap["values"] ?: doubleArrayOf() },
        responseName  = responseTrace.name
    )

    init {
        title  = "Observations for $responseName"
        xLabel = "Observation Number"
        yLabel = "Observation"

        val myIndices = mutableListOf<Int>()
        val myValues  = mutableListOf<Double>()
        val mySeries  = mutableListOf<String>()

        for ((myLabel, myObs) in seriesDataMap) {
            myObs.forEachIndexed { idx, v ->
                myIndices.add(idx + 1)
                myValues.add(v)
                mySeries.add(myLabel)
            }
        }
        myData = mapOf("index" to myIndices, "value" to myValues, "series" to mySeries)
    }

    override fun buildPlot(): Plot {
        return ggplot(myData) +
                geomLine() {
                    x     = "index"
                    y     = "value"
                    color = "series"
                    group = "series"
                } +
                geomPoint() {
                    x     = "index"
                    y     = "value"
                    color = "series"
                } +
                labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
    }
}
