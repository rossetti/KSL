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

package ksl.examples.book.chapter6

import ksl.simulation.Model

/**
 * This example illustrates how to create a simulation model,
 * attach a new model element, set the run length, and
 * run the simulation. The example use the SchedulingEventExamples
 * class to show how actions are used to implement events.
 */
fun main() {
    val m = Model("Scheduling Example")
    SchedulingEventExamples(m.model)
    m.lengthOfReplication = 100.0
//    m.autoPrintSummaryReport = false
    m.simulate()
}
