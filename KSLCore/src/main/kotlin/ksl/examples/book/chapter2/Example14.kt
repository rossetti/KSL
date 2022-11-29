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

import ksl.utilities.distributions.Binomial

/**
 * This example illustrates how to make instances of Distributions.
 * Specifically, a binomial distribution is created, and it is used
 * to compute some properties and to make a random variable. Notice
 * that a distribution is not the same thing as a random variable.
 * Random variables generate values. Distributions describe how the
 * values are distributed. Random variables are immutable. Distributions
 * can have their parameters changed.
 */
fun main() {
    // make and use a Binomial(p, n) distribution
    val n = 10
    val p = 0.8
    println("n = $n")
    println("p = $p")
    val bnDF = Binomial(p, n)
    println("mean = " + bnDF.mean())
    println("variance = " + bnDF.variance())
    // compute some values
    System.out.printf("%3s %15s %15s %n", "k", "p(k)", "cdf(k)")
    for (i in 0..10) {
        System.out.printf("%3d %15.10f %15.10f %n", i, bnDF.pmf(i), bnDF.cdf(i))
    }
    println()
    // change the probability and number of trials
    bnDF.probOfSuccess = 0.5
    bnDF.numTrials = 20
    println("mean = " + bnDF.mean())
    println("variance = " + bnDF.variance())
    // make random variables based on the distributions
    val brv = bnDF.randomVariable
    System.out.printf("%3s %15s %n", "n", "Values")
    // generate some values
    for (i in 1..5) {
        // value property returns generated values
        val x = brv.value.toInt()
        System.out.printf("%3d %15d %n", i, x)
    }
}
