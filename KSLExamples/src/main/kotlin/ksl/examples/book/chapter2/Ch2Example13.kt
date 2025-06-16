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

import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.MixtureRV

/**
 * Example 2.13
 *
 * This example illustrates how to create and use a mixture random variable.
 */
fun main() {
    // rvs is the list of random variables for the mixture
    val rvs = listOf(ExponentialRV(1.5), ExponentialRV(1.1))
    // cdf is the cumulative distribution function over the random variables
    val cdf = doubleArrayOf(0.7, 1.0)
    //create a mixture random variable
    val he = MixtureRV(rvs, cdf, streamNum = 1)
    print(String.format("%3s %15s %n", "n", "Values"))
    for (i in 1..5) {
        print(String.format("%3d %15f %n", i, he.value))
    }
}