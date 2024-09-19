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

package ksl.examples.general.misc

import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.Counter
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

class EntityGeneratorTest(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {

    private val worker: ResourceWithQ = ResourceWithQ(this, "worker")
    private val tba = ExponentialRV(6.0, 1)
    private val st = RandomVariable(this, ExponentialRV(3.0, 2), "Service RV")
    private val wip = TWResponse(this, "${this.name}:WIP")
    private val tip = Response(this, "${this.name}:TimeInSystem")
    private val generator = EntityGenerator(this::Customer, tba, tba)
    private val counter = Counter(this, "${this.name}:NumServed" )

    private inner class Customer: Entity() {
        val mm1: KSLProcess = process("MM1", addToSequence = true){
            wip.increment()
            timeStamp = time
            val a  = seize(worker)
            delay(st)
            release(a)
            tip.value = time - timeStamp
            wip.decrement()
            counter.increment()
        }
    }
}

fun main(){
    val m = Model()
    val test = EntityGeneratorTest(m)
    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
//    m.numberOfReplications = 1
//    m.lengthOfReplication = 20.0
//    m.lengthOfReplicationWarmUp = 5.0
    m.simulate()
    m.print()
}