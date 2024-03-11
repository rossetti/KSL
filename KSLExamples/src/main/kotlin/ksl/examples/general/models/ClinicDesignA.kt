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

import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.BernoulliRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.LognormalRV
import ksl.utilities.random.rvariable.WeibullRV

class ClinicDesignA(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name) {

    private var timeBetweenArrivals: RandomVariable = RandomVariable(parent,
        ExponentialRV(60.0 / 9.5, 1))

    private var needsFollowUp: RandomVariable = RandomVariable(this,
        BernoulliRV(0.05, 2))

    private var paperWorkTime: RandomVariable = RandomVariable(this,
        LognormalRV(6.5, 0.5 * 0.5, 3))

    private var vitalsTime: RandomVariable = RandomVariable(this,
        LognormalRV(6.0, 0.5 * 0.5, 4))

    private var diabetesTestTime: RandomVariable = RandomVariable(this,
        LognormalRV(5.5, 0.5 * 0.5, 5))

    private var schedulingTime: RandomVariable = RandomVariable(this,
        WeibullRV(2.6, 7.3, 6))

    private val wip: TWResponse = TWResponse(this,
        "${this.name}:NumInSystem")

    private val timeInSystem: Response = Response(this,
        "${this.name}:TimeInSystem")
    val systemTime: ResponseCIfc
        get() = timeInSystem

    private val numPatients: Counter = Counter(this,
        "${this.name}:NumPatients")

    private val nurses: ResourceWithQ = ResourceWithQ(this,
        "Nurses", capacity = 3)

    private val schedulingClerk: ResourceWithQ = ResourceWithQ(this,
        "SchedulingClerk", capacity = 1)

    private val patientGenerator = EntityGenerator(
        ::Patient, timeUntilTheFirstEntity = timeBetweenArrivals,
        timeBtwEvents = timeBetweenArrivals,
        timeOfTheLastEvent = 10.0 * 60.0
    )

    private inner class Patient : Entity() {
        val patientProcess: KSLProcess = process() {
            wip.increment()
            timeStamp = time
            val nurse = seize(nurses)
            delay(paperWorkTime)
            delay(vitalsTime)
            delay(diabetesTestTime)
            release(nurse)
            if (needsFollowUp.value == 1.0) {
                use(schedulingClerk, delayDuration = schedulingTime)
            }
            timeInSystem.value = time - timeStamp
            wip.decrement()
            numPatients.increment()
        }
    }
}

