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
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs

/**
 * Plots multiple observation sequences for an observation-based response variable
 * on a single set of axes, with simulation time on the x-axis and the observed
 * value on the y-axis.
 *
 * Each entry in [seriesDataMap] becomes one coloured scatter series.  The outer
 * key is the series label shown in the legend (e.g. `"Rep 1"`); the inner map
 * must contain keys `"times"` and `"values"` holding the simulation times and
 * corresponding observations respectively — the same shape returned by
 * [ResponseTrace.traceDataMap] and [ResponseTrace.traceDataMaps].
 *
 * This is the default visualisation for observation-based [ResponseTrace] data.
 * Using simulation time on the x-axis (rather than observation index) makes the
 * temporal distribution of observations visible and allows warm-up filtering to
 * be directly apparent in the plot.
 *
 * The convenience constructor accepts a [ResponseTrace] directly and handles
 * data extraction and label generation automatically.
 *
 * @param seriesDataMap  series label → `{ "times" → DoubleArray, "values" → DoubleArray }`
 * @param responseName   name used in the plot title and y-axis label
 */
class MultiSeriesScatterPlot(
    seriesDataMap: Map<String, Map<String, DoubleArray>>,
    val responseName: String
) : BasePlot() {

    private val myData: Map<String, List<Any>>

    /**
     * Constructs a multi-series scatter plot directly from a [ResponseTrace].
     *
     * Observation times and values for each replication in [repNums] are
     * retrieved over the time window [[startTime], [endTime]].  Each series
     * is labelled `"Rep N"` where N is the replication number.
     *
     * The default for [repNums] is [ResponseTrace.replicationNumbers] (all
     * recorded replications).  For large traces, pass an explicit subset to
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
            .mapKeys { (repNum, _) -> "Rep $repNum" },
        responseName  = responseTrace.name
    )

    init {
        title  = "Observations over time for $responseName"
        xLabel = "Time"
        yLabel = "Value"

        val myTimes  = mutableListOf<Double>()
        val myValues = mutableListOf<Double>()
        val mySeries = mutableListOf<String>()

        for ((myLabel, myDataMap) in seriesDataMap) {
            val myT = myDataMap["times"]  ?: continue
            val myV = myDataMap["values"] ?: continue
            val myN = minOf(myT.size, myV.size)
            for (i in 0 until myN) {
                myTimes.add(myT[i])
                myValues.add(myV[i])
                mySeries.add(myLabel)
            }
        }
        myData = mapOf("times" to myTimes, "values" to myValues, "series" to mySeries)
    }

    override fun buildPlot(): Plot {
        return ggplot(myData) +
                geomPoint() {
                    x     = "times"
                    y     = "values"
                    color = "series"
                } +
                labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
    }
}
