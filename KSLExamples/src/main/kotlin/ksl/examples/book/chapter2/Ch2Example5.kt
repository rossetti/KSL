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

import ksl.utilities.random.rvariable.KSLRandom

/**
 * Example 2.5
 *
 * This example illustrates how to create a new stream from
 * an existing stream such that the new stream produces the
 * antithetic pseudo-random numbers of the first stream.
 * That is if stream A produces u1, u2, .., then the
 * antithetic of stream A produces 1-u1, 1-u2, ....
 */
fun main() {
    // get the default stream
    val s = KSLRandom.defaultRNStream()
    // make its antithetic version
    val ans = s.antitheticInstance()
    print(String.format("%3s %15s %15s %15s %n", "n", "U", "1-U", "sum"))
    for (i in 1..5) {
        val u = s.randU01()
        val au = ans.randU01()
        print(String.format("%3d %15f %15f %15f %n", i, u, au, u + au))
    }
}
