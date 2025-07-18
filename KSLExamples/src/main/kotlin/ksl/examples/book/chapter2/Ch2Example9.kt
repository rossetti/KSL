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

import ksl.utilities.random.rvariable.NormalRV


/**
 * Example 2.9
 * This example illustrates how to use the classes within the rvariable package.
 * Specifically, a Normal(mean=20, variance=4.0) random variable is
 * created and values are obtained via the value property
 */
fun main() {
    // create a normal mean = 20.0, variance = 4.0 random variable,
    // using stream number 1
    val n = NormalRV(20.0, 4.0, streamNum = 1)
    print(String.format("%3s %15s %n", "n", "Values"))
    // generate some values
    for (i in 1..5) {
        // the value property returns a generated value
        val x = n.value
        print(String.format("%3d %15f %n", i, x))
    }
}
