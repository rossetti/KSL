/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.rvariable

import ksl.utilities.Interval
import ksl.utilities.distributions.Exponential
import ksl.utilities.distributions.PDFIfc
import ksl.utilities.distributions.Uniform
import ksl.utilities.io.plotting.DensityPlot
import ksl.utilities.random.rvariable.AcceptanceRejectionRV
import ksl.utilities.statistic.Histogram
import ksl.utilities.toCSVString
import ksl.utilities.toStrings
import kotlin.math.exp


fun main() {

//    exampleA8()

    partB()
}

/**
 *  Example A.8 Acceptance rejection
 */
fun exampleA8(){
    // proposal distribution
    val wx = Uniform(-1.0, 1.0)
    // majorizing constant, if g(x) is majorizing function, then g(x) = w(x)*c
    val c = 3.0 / 2.0
    val rv = AcceptanceRejectionRV(wx, c, f)
    val h = Histogram.create(-1.0, 1.0, 100)
    for (i in 1..10000) {
        h.collect(rv.value)
    }
    val hp = h.histogramPlot()
    hp.showInBrowser()
    val dp = DensityPlot(h) { x -> f.pdf(x) }
    dp.showInBrowser()
}

object f : PDFIfc {
    override fun pdf(x: Double): Double {
        return (0.75 * (1.0 - x * x))
    }

    override fun domain(): Interval {
        return Interval(-1.0, 1.0)
    }

}

fun partB(){
    val a = 1.0
    // specify the proposal distribution
    val wx = Exponential(1.0)
    // majorizing constant, if g(x) is majorizing function, then g(x) = w(x)*c
    val c = 1.0/(1.0 - exp(-a))
    val f = TExp(a)
    val rv = AcceptanceRejectionRV(wx, c, f)
    val h = Histogram.create(0.0, a, 100)
    for (i in 1..10000) {
        val x = rv.value
        h.collect(x)
    }
    println(h)
    val hp = h.histogramPlot()
    hp.showInBrowser()

    val dp = DensityPlot(h) { x -> f.pdf(x) }
    dp.showInBrowser()
}

class TExp(val a: Double) : PDFIfc {
    private val c = 1.0/(1.0 - exp(-a))
    override fun pdf(x: Double): Double {
        if ((x < 0.0) || (x > a)){
            return 0.0
        }
        return exp(-x) * c
    }

    override fun domain() : Interval {
        return Interval(0.0, a)
    }

}