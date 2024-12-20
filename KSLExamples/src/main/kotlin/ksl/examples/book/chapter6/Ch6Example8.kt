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
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.entity.ResourceWithQCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.*

/**
 *  Example 6.7
 *  This model illustrates process view modeling via the STEM Career Fair Mixer system
 *  described in Chapter 6.
 */
class StemFairMixer(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name) {

    private val myTBArrivals: RVariableIfc = ExponentialRV(2.0, 1)
    private val myNameTagTimeRV = RandomVariable(this, UniformRV((15.0 / 60.0), (45.0 / 60.0), 2))
    private val myWanderingTimeRV = RandomVariable(this, TriangularRV(15.0, 20.0, 45.0, 3),
        name = "WanderingT")
    private val myTalkWithJHBunt = RandomVariable(this, ExponentialRV(6.0, 4))
    private val myTalkWithMalMart = RandomVariable(this, ExponentialRV(3.0, 5))
    private val myDecideToWander = RandomVariable(this, BernoulliRV(0.5, 6))
    private val myDecideToLeave = RandomVariable(this, BernoulliRV(0.1, 7))

    private val myOverallSystemTime = Response(this, "OverallSystemTime")
    private val mySystemTimeNW = Response(this, "NonWanderSystemTime")
    private val mySystemTimeW = Response(this, "WanderSystemTime")
    private val mySystemTimeL = Response(this, "LeaverSystemTime")
    private val myNumInSystem = TWResponse(this, "NumInSystem")

    private val myJHBuntRecruiters: ResourceWithQ = ResourceWithQ(this, capacity = 3, name = "JHBuntR")
    val jhBuntRecruiters : ResourceWithQCIfc
        get() = myJHBuntRecruiters

    private val myMalWartRecruiters: ResourceWithQ = ResourceWithQ(this, capacity = 2, name = "MalWartR")
    val malWartRecruiters : ResourceWithQCIfc
        get() = myMalWartRecruiters

    private val generator = EntityGenerator(::Student, myTBArrivals, myTBArrivals)

    private inner class Student : Entity() {
        private val isWanderer = myDecideToWander.value.toBoolean()
        private val isLeaver = myDecideToLeave.value.toBoolean()

        val stemFairProcess = process(isDefaultProcess = true) {
            myNumInSystem.increment()
            delay(myNameTagTimeRV)
            if (isWanderer) {
                delay(myWanderingTimeRV)
                if (isLeaver) {
                    departMixer(this@Student)
                    return@process
                }
            }
            val mw = seize(myMalWartRecruiters)
            delay(myTalkWithMalMart)
            release(mw)
            val jhb = seize(myJHBuntRecruiters)
            delay(myTalkWithJHBunt)
            release(jhb)
            departMixer(this@Student)
        }

        private fun departMixer(departingStudent: Student) {
            myNumInSystem.decrement()
            val st = time - departingStudent.createTime
            myOverallSystemTime.value = st
            if (isWanderer) {
                mySystemTimeW.value = st
                if (isLeaver) {
                    mySystemTimeL.value = st
                }
            } else {
                mySystemTimeNW.value = st
            }
        }

    }
}

fun main() {
    val m = Model()
    StemFairMixer(m, "Stem Fair")
    m.lengthOfReplication = 6.0 * 60.0
    m.numberOfReplications = 400
    m.simulate()
    m.print()
}