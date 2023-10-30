/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.examples.general.utilities

import ksl.utilities.random.rvariable.LognormalRV
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.statistic.BSEstimatorIfc
import ksl.utilities.statistic.JackKnifeEstimator

fun main() {
    jackKnifeEx1()
    jackKnifeEx2()
}

fun jackKnifeEx1() {
    val n = NormalRV(10.0, 3.0)
    val bs = JackKnifeEstimator(n.sample(50), BSEstimatorIfc.Average())
    println(bs)
}

fun jackKnifeEx2() {
    val n = LognormalRV(10.0, 3.0)
    val bs = JackKnifeEstimator(n.sample(50), BSEstimatorIfc.Minimum())
    println(bs)
}