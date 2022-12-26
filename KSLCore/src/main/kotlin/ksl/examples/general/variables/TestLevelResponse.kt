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

import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.Interval
import ksl.utilities.random.rvariable.NormalRV

class TestLevelResponse(parent: ModelElement, name: String? = null) : ModelElement(parent, name) {
    private val myRV: RandomVariable = RandomVariable(this, NormalRV(0.0, 1.0, 1))
    private val myVariable: Variable = Variable(this, name = "Level Variable")
    private val myLR: LevelResponse = LevelResponse(myVariable, 0.0)
    private val myR: Response = Response(this, allowedDomain = Interval(), name = "Observations")

    override fun initialize() {
        schedule(this::variableUpdate, 1.0);
    }

    private fun variableUpdate(event: KSLEvent<Nothing>) {
        val x: Double = myRV.value
        myVariable.value = x
        myR.value = x
        schedule(this::variableUpdate, 1.0);
        //System.out.println("in variable update");
    }

}

fun main() {
    val s = Model("Temp")
    TestLevelResponse(s)
    s.numberOfReplications = 10
    s.lengthOfReplication = 10000.0
    s.simulate()
    s.print()
}