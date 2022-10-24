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
 * This example illustrates the simulation of the up/down component model
 * and the turning on of event tracing and the log report.
 */
fun main() {
    // create the simulation model
    val m = Model("UpDownComponent")
    // create the model element and attach it to the model
    val tv = UpDownComponent(m)
    // set the running parameters of the simulation
    m.numberOfReplications = 5
    m.lengthOfReplication = 5000.0
    // tell the simulation model to run
    m.simulate()

    m.simulationReporter.printAcrossReplicationSummaryStatistics()
}
