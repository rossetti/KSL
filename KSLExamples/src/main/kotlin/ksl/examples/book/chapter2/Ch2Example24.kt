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

package ksl.examples.book.chapter2

import ksl.utilities.distributions.Exponential
import ksl.utilities.distributions.fitting.ContinuousCDFGoodnessOfFit
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.distributions.fitting.estimators.NormalMLEParameterEstimator
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.statistic.BootstrapSampler

/**
 *  Example 2.24
 *  Illustrates how to perform goodness of fit testing.
 */
fun main() {
    val d = Exponential(10.0)
    val e = d.randomVariable()
    e.advanceToNextSubStream()
    val n = 1000
    val data = e.sample(n)
    val gof = ContinuousCDFGoodnessOfFit(data, d)
    println(gof)
}