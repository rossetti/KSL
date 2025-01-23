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

package ksl.examples.book.chapter7

import ksl.modeling.elements.EventGenerator
import ksl.modeling.entity.*
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.io.KSL
import ksl.utilities.io.MarkDown
import ksl.utilities.random.rvariable.BernoulliRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.toBoolean

class ResourcePoolExample(parent: ModelElement) : ProcessModel(parent, null) {

    private val john = Resource(this, name = "John")
    private val paul = Resource(this, name = "Paul")
    private val george = Resource(this, name = "George")
    private val ringo = Resource(this, name = "Ringo")
    private val list1 = listOf(john, paul, george)
    private val list2 = listOf(ringo, george)
    private val pool1: ResourcePoolWithQ = ResourcePoolWithQ(this, list1, name = "pool1")
    private val pool2: ResourcePoolWithQ = ResourcePoolWithQ(this, list2, name = "pool2")

    init {
        val rule = LeastUtilizedAllocationRule()
        pool1.defaultResourceAllocationRule = rule
        pool2.defaultResourceAllocationRule = rule
    }
    private val tba = RandomVariable(this, ExponentialRV(1.0, 1), "Arrival RV")
    private val st = RandomVariable(this, ExponentialRV(3.0, 2), "Service RV")
    private val decideProcess = RandomVariable(this, BernoulliRV(0.5, 3))
    private val wip1 = TWResponse(this, "${name}:WIP1")
    private val tip1 = Response(this, "${name}:TimeInSystem1")
    private val wip2 = TWResponse(this, "${name}:WIP2")
    private val tip2 = Response(this, "${name}:TimeInSystem2")
    private val generator = EventGenerator(this, this::arrivals, tba, tba)

    private fun arrivals(generator: EventGenerator){
        val c = Customer()
        if (decideProcess.value.toBoolean()){
            activate(c.usePool1)
        } else {
            activate(c.usePool2)
        }
    }

    private inner class Customer: Entity() {
        val usePool1: KSLProcess = process("Pool 1 Process") {
            wip1.increment()
            timeStamp = time
            val a  = seize(pool1, 1)
            delay(st)
            release(a)
            tip1.value = time - timeStamp
            wip1.decrement()
        }

        val usePool2: KSLProcess = process("Pool 2 Process") {
            wip2.increment()
            timeStamp = time
            val a  = seize(pool2, 1)
            delay(st)
            release(a)
            tip2.value = time - timeStamp
            wip2.decrement()
        }
    }

}

fun main(){
    val m = Model()
    val test = ResourcePoolExample(m)
    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
    val r = m.simulationReporter
    r.writeHalfWidthSummaryReportAsMarkDown(KSL.out, df = MarkDown.D3FORMAT)
}