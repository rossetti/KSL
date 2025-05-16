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

import ksl.utilities.random.rvariable.GammaRV
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.random.rvariable.exp
import ksl.utilities.statistics

/**
 *  Example 2.21
 *  Illustrates how to create functions of random variables and use them
 *  to generate random variates.
 */
fun main(){
    // define a lognormal random variable, y
    val x = NormalRV(2.0, 5.0, streamNum = 1)
    val y = exp(x)
    // generate from y
    println("stream number = ${y.streamNumber}")
    val ySample = y.sample(1000)
    println(ySample.statistics())
    // define a beta random variable in terms of gamma
    val alpha1 = 2.0
    val alpha2 = 5.0
    val y1 = GammaRV(alpha1, 1.0, streamNum = 2)
    val y2 = GammaRV(alpha2, 1.0)
    val betaRV = y1/(y1+y2)
    val betaSample = betaRV.sample(500)
    println("stream number = ${betaRV.streamNumber}")
    println(betaSample.statistics())
}