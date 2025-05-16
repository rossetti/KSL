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
import ksl.utilities.statistic.CachedHistogram
import ksl.utilities.statistic.Histogram

/**
 * Example 2.1a  This is the same as example 2.1 except using a CachedHistogram.
 *
 * This example illustrates how to make an instance of a CachedHistogram
 * and use it to collect statistics on a randomly generated sample.
 */
fun main() {
    val d = ExponentialRV(2.0, streamNum = 1)
    val data = d.sample(1000)
    val ch = CachedHistogram()
    for (x in data) {
        ch.collect(x)
    }
    println(ch)
    val plot = ch.histogramPlot()
    plot.showInBrowser("Exponentially Distributed Data")
}