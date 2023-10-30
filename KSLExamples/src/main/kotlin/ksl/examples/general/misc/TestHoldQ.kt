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

import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

class TestHoldQ(parent: ModelElement) : ProcessModel(parent, null) {

    private val myHoldQueue = HoldQueue(this, "hold")
    private val tba = RandomVariable(this, ExponentialRV(6.0, 1), "Arrival RV")

    private val arrivals = Arrivals()
    private var x = 0

    private inner class Customer: Entity() {
        val holdTest: KSLProcess = process("holdTest"){
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = $id : current value of x, ${x}" }
            println("$time entity_id = $id : x = $x")
            schedule(::releaseHold, 10.0, message = entity)
            println("$time > before hold")
            hold(myHoldQueue)
            println("$time > after hold")
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > entity_id = $id : incrementing x to, ${++x}" }
            println("$time entity_id = $id : x = $x")
        }
    }

    override fun initialize() {
        arrivals.schedule(tba)
    }

    private fun releaseHold(event: KSLEvent<Entity>){
        println("$time > entity_id = $id : before removeAndResume()")
        myHoldQueue.removeAndResume(event.message!!)
        //myHoldQueue.removeAndImmediateResume(event.message!!)
        println("$time > entity_id = $id : after removeAndResume()")
    }

    private inner class Arrivals: EventAction<Nothing>(){
        override fun action(event: KSLEvent<Nothing>) {
            val c = Customer()
            activate(c.holdTest)
            schedule(tba)
        }
    }

}

fun main(){
    val m = Model()
    val test = TestHoldQ(m)
    m.numberOfReplications = 1
    m.lengthOfReplication = 20.0
    m.simulate()
    m.print()
}