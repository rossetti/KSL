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
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.io.asMarkDownTable
import ksl.utilities.io.dbutil.KSLDatabaseObserver
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVariableIfc
import org.jetbrains.kotlinx.dataframe.io.toStandaloneHTML

/**
 *  Example 6.1
 *  This example illustrates how to represent the previously presented drive through pharmacy model
 *  Example 4.4 using process view constructs. The creation of the model and its simulation
 *  is exactly as previously demonstrated. However, the model is different because it implements
 *  the customer's process within a coroutine.
 */
fun main(){
    val m = Model()
    val dtp = DriveThroughPharmacy(m, name = "DriveThrough")
    dtp.arrivalRV.initialRandomSource = ExponentialRV(6.0, 1)
    dtp.serviceRV.initialRandomSource = ExponentialRV(3.0, 2)
    m.numberOfReplications = 30
    m.lengthOfReplication = 20000.0
    m.lengthOfReplicationWarmUp = 5000.0
    m.simulate()
    m.print()
}

class DriveThroughPharmacy(
    parent: ModelElement,
    numPharmacists: Int = 1,
    name: String? = null
) : ProcessModel(parent, name) {
    init {
        require(numPharmacists > 0) { "The number of pharmacists must be >= 1" }
    }

    private val pharmacists: ResourceWithQ = ResourceWithQ(
        parent = this,
        name = "Pharmacists",
        capacity = numPharmacists
    )

    private val serviceTime: RandomVariable = RandomVariable(parent = this, rSource = ExponentialRV(0.5, 2))
    val serviceRV: RandomVariableCIfc
        get() = serviceTime
    private val timeBetweenArrivals: RandomVariable = RandomVariable(
        parent = parent,
        rSource = ExponentialRV(1.0, 1)
    )
    val arrivalRV: RandomVariableCIfc
        get() = timeBetweenArrivals
    private val wip: TWResponse = TWResponse(parent = this, name = "${this.name}:NumInSystem")
    val numInSystem: TWResponseCIfc
        get() = wip
    private val timeInSystem: Response = Response(parent = this, name = "${this.name}:TimeInSystem")
    val systemTime: ResponseCIfc
        get() = timeInSystem
    private val numCustomers: Counter = Counter(parent = this, name = "${this.name}:NumServed")
    val numCustomersServed: CounterCIfc
        get() = numCustomers
    private val mySTGT4: IndicatorResponse = IndicatorResponse(
        predicate = { x -> x >= 4.0 },
        observedResponse = timeInSystem,
        name = "SysTime > 4.0 minutes"
    )
    val probSystemTimeGT4Minutes: ResponseCIfc
        get() = mySTGT4

    override fun initialize() {
        schedule(eventAction = this::arrival, timeToEvent = timeBetweenArrivals)
    }

    private fun arrival(event: KSLEvent<Nothing>) {
        val c = Customer()
        activate(c.pharmacyProcess)
        schedule(eventAction = this::arrival, timeToEvent = timeBetweenArrivals)
    }

    private inner class Customer : Entity() {
        val pharmacyProcess: KSLProcess = process {
            wip.increment()
            timeStamp = time
            val a = seize(resource = pharmacists)
            delay(delayDuration = serviceTime)
            release(allocation = a)
            timeInSystem.value = time - timeStamp
            wip.decrement()
            numCustomers.increment()
        }
    }
}

