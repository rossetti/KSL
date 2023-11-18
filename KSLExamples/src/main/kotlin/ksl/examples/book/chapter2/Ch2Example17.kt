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

import ksl.utilities.distributions.fitting.NormalMLEParameterEstimator
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.random.rvariable.GammaRV
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.random.rvariable.exp
import ksl.utilities.statistic.BootstrapSampler
import ksl.utilities.statistics

fun main(){
    // define a normal random variable,
    val x = NormalRV(2.0, 5.0)
    // generate some data
    val data = x.sample(100)
    // create the estimator
    val ne = NormalMLEParameterEstimator
    // estimate the parameters
    val result = ne.estimateParameters(data)
    println(result)
    val bss = BootstrapSampler(data, ne)
    val list = bss.bootStrapEstimates(400)
    for (element in list) {
        println(element.toString())
    }
}