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

import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement

class Example2(parent: ModelElement) : ProcessModel(parent, null)  {

    private val myHoldQueue: HoldQueue = HoldQueue(this, "hold")

    private val myEventActionOne: EventActionOne = EventActionOne()

    private inner class Customer: ProcessModel.Entity() {
        val holdProcess : KSLProcess = process() {
            println("time = $time : before being held customer = ${this@Customer.name}")
            hold(myHoldQueue)
            println("time = $time : after being held customer = ${this@Customer.name}")
            delay(10.0)
            println("time = $time after the first delay for customer = ${this@Customer.name}")
            delay(20.0)
            println("time = $time after the second delay for customer = ${this@Customer.name}")
        }
    }

    override fun initialize() {
        val e = Customer()
        activate(e.holdProcess)
        val c = Customer()
        activate(c.holdProcess, 1.0)
        schedule(myEventActionOne, 5.0)
    }

    private inner class EventActionOne : ModelElement.EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            println("Removing and resuming held entities at time : $time")
            myHoldQueue.removeAllAndResume()
        }
    }
}

fun main(){
    val m = Model()
    Example2(m)
    m.lengthOfReplication = 50.0
    m.numberOfReplications = 1
    m.simulate()
}