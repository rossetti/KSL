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

import ksl.utilities.random.rvariable.*

/**
 * Example 2.15
 *
 * This example illustrates how to create and use a shifted random variable.
 */
fun main() {
    val w = WeibullRV(shape = 3.0, scale = 5.0)
    val rv = ShiftedRV(5.0, w, streamNum = 1)
    print(String.format("%3s %15s %n", "n", "Values"))
    for (i in 1..5) {
        print(String.format("%3d %15f %n", i, rv.value))
    }
}