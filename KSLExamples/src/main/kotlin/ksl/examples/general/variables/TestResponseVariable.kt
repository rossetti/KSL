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

package ksl.examples.general.variables

import ksl.modeling.variable.Response
import ksl.observers.ResponseTraceCSV
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.simulation.SimulationReporter


class TestResponseVariable(parent: ModelElement, name: String? = null) : ModelElement(parent, name) {
    private val myRS: Response = Response(this, "test constants")

    init {
        ResponseTraceCSV(myRS)
    }

    override fun initialize() {
        schedule(this::doTest, 2.0);
    }

    private fun doTest(e: KSLEvent<Nothing>) {
        myRS.value = 2.0
        schedule(this::doTest, 2.0);
    }

}
fun main() {
    val sim = Model("test RS")
    TestResponseVariable(sim)
    sim.turnOnReplicationCSVStatisticReporting()
    sim.numberOfReplications = 5
    sim.lengthOfReplication = 25.0
    sim.simulate()
    sim.print()
}