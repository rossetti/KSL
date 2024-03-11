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

package ksl.examples.general.models

import ksl.examples.book.chapter4.DriveThroughPharmacyWithQ
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV

class SimpleServiceSystem(
    parent: ModelElement,
    numServers: Int = 1,
    ad: RandomIfc = ExponentialRV(1.0, 1),
    sd: RandomIfc = ExponentialRV(0.5, 2),
    name: String? = null
) : ProcessModel(parent, name) {
    init {
        require(numServers > 0) { "The number of servers must be >= 1" }
    }

    private val servers: ResourceWithQ = ResourceWithQ(this, "Servers", numServers)
    private var serviceTime: RandomVariable = RandomVariable(this, sd)
    private var timeBetweenArrivals: RandomVariable = RandomVariable(parent, ad)
    private val wip: TWResponse = TWResponse(this, "${this.name}:NumInSystem")
    private val timeInSystem: Response = Response(this, "${this.name}:TimeInSystem")
    private val numCustomers: Counter = Counter(this, "${this.name}:NumServed")

    override fun initialize() {
        schedule(this::arrival, timeBetweenArrivals)
    }

    private fun arrival(event: KSLEvent<Nothing>) {
        val c = Customer()
        activate(c.serviceProcess)
        schedule(this::arrival, timeBetweenArrivals)
    }

    private inner class Customer : Entity() {
        val serviceProcess: KSLProcess = process() {
            wip.increment()
            timeStamp = time
            val a = seize(servers)
            delay(serviceTime)
            release(a)
            timeInSystem.value = time - timeStamp
            wip.decrement()
            numCustomers.increment()
        }
    }
}

fun main(){
    val sim = Model("MM1 Model")
    sim.numberOfReplications = 30
    sim.lengthOfReplication = 20000.0
    sim.lengthOfReplicationWarmUp = 5000.0
    SimpleServiceSystem(sim, 1, name = "MM1")
    sim.simulate()
    sim.print()
}

