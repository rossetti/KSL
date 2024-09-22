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

class WaitForProcessExample(parent: ModelElement) : ProcessModel(parent, null) {
    private val worker: ResourceWithQ = ResourceWithQ(this, "worker", 1)
    private val tba = RandomVariable(this, ExponentialRV(6.0, 1), "Arrival RV")
    private val st = RandomVariable(this, ExponentialRV(3.0, 2), "Service RV")
    private val wip = TWResponse(this, "${name}:WIP")
    private val tip = Response(this, "${name}:TimeInSystem")
    private val arrivals = Arrivals()
    private val total = 1
    private var n = 1

    private inner class Customer : Entity() {
        val simpleProcess: KSLProcess = process("SimpleProcess") {
            println("\t $time > starting simple process for entity: ${this@Customer.name}")
            wip.increment()
            timeStamp = time
            use(worker, delayDuration = st)
            tip.value = time - timeStamp
            wip.decrement()
            println("\t $time > completed simple process for entity: ${this@Customer.name}")
        }

        val wfp = process("WaitForAnotherProcess") {
            val c = Customer()
            println("$time > before waitFor simple process for entity: ${this@Customer.name}")
            waitFor(c.simpleProcess)
            println("$time > after waitFor simple process for entity: ${this@Customer.name}")
        }
    }

    override fun initialize() {
        arrivals.schedule(tba)
    }

    private inner class Arrivals : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            if (n <= total) {
                val c = Customer()
                println("$time > activating the waitFor process for entity: ${c.name}")
                activate(c.wfp)
                schedule(tba)
                n++
            }
        }
    }

}

fun main() {
    val m = Model()
    WaitForProcessExample(m)
    m.numberOfReplications = 1
    m.lengthOfReplication = 200.0
    m.simulate()
    m.print()
}