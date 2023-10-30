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
import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ResourceWithQ
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement

class TestProcessModeling(parent: ModelElement) : ProcessModel(parent, null) {

    val resource: ResourceWithQ = ResourceWithQ(this, "test resource")

    private val myHoldQueue = HoldQueue(this, "hold")

    private val myEventActionOne: EventActionOne = EventActionOne()

    private inner class Customer: Entity() {
        val someProcess : KSLProcess = process("test") {
            println("time = $time before the first delay in ${this@Customer}")
            hold(myHoldQueue)
            delay(10.0)
            println("time = $time after the first delay in ${this@Customer}")
            println("time = $time before the second delay in ${this@Customer}")
            delay(20.0)
            println("time = $time after the second delay in ${this@Customer}")
        }

        val seizeTest: KSLProcess = process("test seize"){
            val a  = seize(resource)
            delay(10.0)
            release(a)
        }
    }

    private var customer: Customer? = null

    override fun initialize() {
        val e = Customer()
        customer = e
        activate(e.someProcess)
        val c = Customer()
        activate(c.someProcess, 1.0)

//        val t = Customer()
//        activate(t.seizeTest)
//        activate(c.seizeTest, 1.0)
        schedule(myEventActionOne, 5.0)
    }

    private inner class EventActionOne : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            println("EventActionOne at time : $time")
           // customer?.terminateProcess()
            myHoldQueue.removeAllAndResume()
        }
    }
}

fun main(){
    val m = Model()
    val test = TestProcessModeling(m)

    m.lengthOfReplication = 100.0
    m.numberOfReplications = 1
    m.simulate()
}