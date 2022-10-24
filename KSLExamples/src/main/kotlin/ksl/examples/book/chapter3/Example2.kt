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

package ksl.examples.book.chapter3

import ksl.utilities.random.rvariable.TriangularRV

/**
 * This example illustrates how to use the classes within the rvariable package.
 * Specifically, a Triangular( min = 2.0, mode = 5.0, max = 10.0) random variable is
 * created and values are obtained via the sample() method.
 */
fun main() {
    // create a triangular random variable with min = 2.0, mode = 5.0, max = 10.0
    val t = TriangularRV(2.0, 5.0, 10.0)
    // sample 5 values
    val sample = t.sample(5)
    System.out.printf("%3s %15s %n", "n", "Values")
    for (i in sample.indices) {
        System.out.printf("%3d %15f %n", i + 1, sample[i])
    }
}