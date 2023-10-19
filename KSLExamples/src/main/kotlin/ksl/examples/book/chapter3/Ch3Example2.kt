/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.examples.book.chapter3

import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.Histogram

/**
 * This example illustrates how to make an instance of a Histogram
 * and use it to collect statistics on a randomly generated sample.
 */
fun main() {
    val d = ExponentialRV(2.0)

    val data = d.sample(1000)
    var bp = Histogram.recommendBreakPoints(data)
    bp = Histogram.addPositiveInfinity(bp)
    val h = Histogram(breakPoints = bp)
    for (x in data) {
        h.collect(x)
    }
    println(h)
    val plot = h.histogramPlot()
    plot.showInBrowser("Exponentially Distributed Data")
}