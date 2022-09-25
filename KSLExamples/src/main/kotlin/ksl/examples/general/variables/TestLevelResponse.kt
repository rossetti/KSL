/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
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
    private val myVariable: TWResponse = TWResponse(this, theLimits = Interval(), name = "Level Variable")
    private val myLR: LevelResponse = LevelResponse(myVariable, 0.0)
    private val myR: Response = Response(this, theLimits = Interval(), name = "Observations")

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