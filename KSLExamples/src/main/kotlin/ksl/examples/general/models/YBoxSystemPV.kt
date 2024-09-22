/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.models

import ksl.modeling.elements.EventGeneratorCIfc
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.Counter
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.BernoulliRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.UniformRV
import ksl.utilities.random.rvariable.toBoolean

class YBoxSystemPV(parent: ModelElement, name: String?= null) : ProcessModel(parent, name) {
    private val maxAdjustments = 2
    private var myArrivalRV: RandomVariable = RandomVariable(parent, ExponentialRV(15.0, 1))
    private val myArrivalGenerator = EntityGenerator(::YBox,
        "YBox Process", myArrivalRV, myArrivalRV)
    val generator: EventGeneratorCIfc
        get() = myArrivalGenerator

    private val inspectors: ResourceWithQ = ResourceWithQ(this, capacity = 2, name = "Inspectors")
    private val adjustors: ResourceWithQ = ResourceWithQ(this, capacity = 1, name = "Adjustor")

    private var myInspectionRV: RandomVariable = RandomVariable(this, ExponentialRV(10.0, 2))
    private var myAdjustmentRV: RandomVariable = RandomVariable(this, UniformRV(7.0, 14.0, 3))
    private var myPassInspectionRV: RandomVariable = RandomVariable(this, BernoulliRV(0.82, 4))
    private val mySysTime: Response = Response(this, "System Time")
    val systemTime: ResponseCIfc
        get() = mySysTime
    private val myNumScrapped: Counter = Counter(this, "Count Scrapped")

    private inner class YBox : Entity(){
        var numAdjustments = 0
        val productionProcess = process("YBox Process"){
            do {
                val a = seize(inspectors, amountNeeded = 1)
                delay(myInspectionRV)
                release(a)
                val passed = myPassInspectionRV.value.toBoolean()
                if (!passed){
                    val a2 = seize(adjustors)
                    delay(myAdjustmentRV)
                    release(a2)
                    numAdjustments++
                } else {
                    break
                }
            } while (numAdjustments <= maxAdjustments)

            if (numAdjustments == maxAdjustments){
                myNumScrapped.increment()
            } else {
                mySysTime.value = time - createTime
            }
        }
    }
}

fun main() {
    val model = Model("YBox System")
    // add the model element to the main model
    val yBoxSystem = YBoxSystemPV(model)
    model.numberOfReplications = 5
    model.lengthOfReplication = 30000.0
    // simulate the model
    model.simulate()
    model.print()
}