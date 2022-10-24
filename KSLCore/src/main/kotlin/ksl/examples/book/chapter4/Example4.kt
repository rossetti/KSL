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

package ksl.examples.book.chapter4

import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.statistic.StateFrequency

/**
 * This example illustrates how to define labeled states
 * and to tabulate observations of those states. The StateFrequency
 * class generalizes the IntegerFrequency class by allowing the user
 * to collect observations on labeled states rather than integers.
 * This also allows for the tabulation of counts and proportions of single
 * step transitions between states.
 */
fun main() {
    val sf = StateFrequency(6)
    val states = sf.states
    for (i in 1..10000) {
        val state = KSLRandom.randomlySelect(states)
        sf.collect(state)
    }
    println(sf)
}
