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

package ksl.examples.book.chapter2

import ksl.utilities.random.rvariable.KSLRandom.rNormal
import ksl.utilities.random.rvariable.KSLRandom.rPoisson
import ksl.utilities.random.rvariable.KSLRandom.rUniform

/**
 * Example 2.17
 * This example illustrates that the user can use the static methods
 * of KSLRandom to generate from any of the defined random variables
 * as simple function calls.
 */
fun main() {
    val v = rUniform(10.0, 15.0) // generate a U(10, 15) value
    val x = rNormal(5.0, 2.0) // generate a Normal(mu=5.0, var= 2.0) value
    val n = rPoisson(4.0).toDouble() //generate from a Poisson(mu=4.0) value
    print(String.format("v = %f, x = %f, n = %f %n", v, x, n))
}
