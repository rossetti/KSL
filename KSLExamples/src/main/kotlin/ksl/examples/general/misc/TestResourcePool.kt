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

import ksl.modeling.entity.*
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

class TestResourcePool(parent: ModelElement) : ProcessModel(parent, null) {
    private val pool: ResourcePoolWithQ = ResourcePoolWithQ(
        this,
        Resource.createResources(this, 3),
        name = "pool")

    init {
        pool.defaultResourceAllocationRule= LeastUtilizedResourceAllocationRule()
    }
    private val tba = RandomVariable(this, ExponentialRV(6.0, 1), "Arrival RV")
    private val st = RandomVariable(this, ExponentialRV(3.0, 2), "Service RV")
    private val wip = TWResponse(this, "${name}:WIP")
    private val tip = Response(this, "${name}:TimeInSystem")
    private val arrivals = Arrivals()

    private inner class Customer: Entity() {
        val mm1: KSLProcess = process("MM1"){
            wip.increment()
            timeStamp = time
            val a  = seize(pool, 1)
            delay(st)
            release(a)
            tip.value = time - timeStamp
            wip.decrement()
        }
    }

    override fun initialize() {
        arrivals.schedule(tba)
    }

    private inner class Arrivals: EventAction<Nothing>(){
        override fun action(event: KSLEvent<Nothing>) {
            val c = Customer()
            activate(c.mm1)
            schedule(tba)
        }
    }

}

fun main(){
    val m = Model()
    val test = TestResourcePool(m)
    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
}