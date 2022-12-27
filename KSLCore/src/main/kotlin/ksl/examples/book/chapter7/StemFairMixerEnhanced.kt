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

package ksl.examples.book.chapter7

import ksl.modeling.elements.EventGenerator
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.nhpp.NHPPTimeBtwEventRV
import ksl.modeling.nhpp.PiecewiseConstantRateFunction
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.divideConstant
import ksl.utilities.random.rvariable.*

class StemFairMixerEnhanced(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {

    var lengthOfMixer = 360.0
        set(value) {
            require(value > 0.0) { "The length of the mixer must be > 0.0" }
            field = value
        }
    var warningTime = 15.0
        set(value) {
            require(value > 0.0) { "The warning limit must be > 0.0" }
            field = value
        }

    val doorClosingTime
        get() = lengthOfMixer - warningTime

    var isClosed: Boolean = false
        private set

    private val myNameTagTimeRV = RandomVariable(this, UniformRV((15.0 / 60.0), (45.0 / 60.0), 2))
    private val myDecideToMix = RandomVariable(this, BernoulliRV(0.4, 3))
    private val myDecideToLeave = RandomVariable(this, BernoulliRV(0.1, 4))
    private val myInteractionTimeRV = RandomVariable(this, TriangularRV(10.0, 15.0, 30.0, 5))
    private val myDecideRecruiter = RandomVariable(this, BernoulliRV(0.5, 6))
    private val myTalkWithJHBunt = RandomVariable(this, ExponentialRV(6.0, 7))
    private val myTalkWithMalMart = RandomVariable(this, ExponentialRV(3.0, 8))
    private val myWalkingSpeedRV = TriangularRV(88.0, 176.0, 264.0, 9)

    private val walkToNameTags = RandomVariable(this, 20.0 / myWalkingSpeedRV)
    private val walkFromNameTagsToConversationArea = RandomVariable(this, 30.0 / myWalkingSpeedRV)
    private val walkFromNameTagsToRecruiting = RandomVariable(this, 80.0 / myWalkingSpeedRV)
    private val walkFromNameTagsToExit = RandomVariable(this, 140.0 / myWalkingSpeedRV)
    private val walkFromConversationAreaToRecruiting = RandomVariable(this, 50.0 / myWalkingSpeedRV)
    private val walkFromConversationAreaToExit = RandomVariable(this, 110.0 / myWalkingSpeedRV)
    private val walkFromRecruitingToExit = RandomVariable(this, 140.0 / myWalkingSpeedRV)

    private val myOverallSystemTime = Response(this, "OverallSystemTime")
    private val myRecruitingOnlySystemTime = Response(this, "RecruitingOnlySystemTime")

    init {
        myRecruitingOnlySystemTime.attachIndicator({ x -> x <= 30.0 }, "P(Recruiting Only < 30 minutes)")
    }

    private val myMixingStudentSystemTime = Response(this, "MixingStudentSystemTime")
    private val myMixingAndLeavingSystemTime = Response(this, "MixingStudentThatLeavesSystemTime")
    private val myNumInSystem = TWResponse(this, "NumInSystem")
    private val myNumInConversationArea = TWResponse(this, "NumInConversationArea")

    private val myNumAtJHBuntAtClosing = Response(this, "NumAtJHBuntAtClosing")
    private val myNumAtMalWartAtClosing = Response(this, "NumAtMalWartAtClosing")
    private val myNumAtConversationAtClosing = Response(this, "NumAtConversationAtClosing")
    private val myNumInSystemAtClosing = Response(this, "NumInSystemAtClosing")

    private val myJHBuntRecruiters: ResourceWithQ = ResourceWithQ(this, capacity = 3, name = "JHBuntR")
    private val myMalWartRecruiters: ResourceWithQ = ResourceWithQ(this, capacity = 2, name = "MalWartR")

    private val myTotalAtRecruiters: AggregateTWResponse = AggregateTWResponse(this, "StudentsAtRecruiters")
    init {
        myTotalAtRecruiters.observe(myJHBuntRecruiters.numBusyUnits)
        myTotalAtRecruiters.observe(myJHBuntRecruiters.waitingQ.numInQ)
        myTotalAtRecruiters.observe(myMalWartRecruiters.numBusyUnits)
        myTotalAtRecruiters.observe(myMalWartRecruiters.waitingQ.numInQ)
    }

    private val myTBArrivals: NHPPTimeBtwEventRV
//    private val myTBArrivals: RVariableIfc

    init {
        // set up the generator
        val durations = doubleArrayOf(
            30.0, 30.0, 30.0, 30.0, 30.0, 30.0,
            30.0, 30.0, 30.0, 30.0, 30.0, 30.0
        )
        val hourlyRates = doubleArrayOf(
            5.0, 10.0, 15.0, 25.0, 40.0, 50.0,
            55.0, 55.0, 60.0, 30.0, 5.0, 5.0
        )
        val ratesPerMinute = hourlyRates.divideConstant(60.0)
        val f = PiecewiseConstantRateFunction(durations, ratesPerMinute)
        myTBArrivals = NHPPTimeBtwEventRV(this, f, streamNum = 1)
//        myTBArrivals = ExponentialRV(2.0, 1)
    }

    private val generator = EventGenerator(this, this::createStudents, myTBArrivals, myTBArrivals)

    private val hourlyResponseSchedule = ResponseSchedule(this, 0.0, name = "Hourly")
    private val peakResponseInterval: ResponseInterval = ResponseInterval(this, 120.0, "PeakPeriod:[150.0,270.0]")

    init {
        hourlyResponseSchedule.addIntervals(0.0, 6, 60.0)
        hourlyResponseSchedule.addResponseToAllIntervals(myJHBuntRecruiters.numBusyUnits)
        hourlyResponseSchedule.addResponseToAllIntervals(myMalWartRecruiters.numBusyUnits)
        hourlyResponseSchedule.addResponseToAllIntervals(myJHBuntRecruiters.waitingQ.timeInQ)
        hourlyResponseSchedule.addResponseToAllIntervals(myMalWartRecruiters.waitingQ.timeInQ)
        peakResponseInterval.startTime = 150.0
        peakResponseInterval.addResponseToInterval(myTotalAtRecruiters)
    }

    override fun initialize() {
        isClosed = false
        schedule(this::closeMixer, doorClosingTime)
    }

    private fun createStudents(eventGenerator: EventGenerator) {
        val student = Student()
        if (student.isMixer) {
            activate(student.mixingStudentProcess)
        } else {
            activate(student.recruitingOnlyStudentProcess)
        }
    }

    private fun closeMixer(event: KSLEvent<Nothing>) {
        isClosed = true
        // turn off the generator
        generator.turnOffGenerator()
        // collect statistics
        myNumAtJHBuntAtClosing.value = myJHBuntRecruiters.waitingQ.numInQ.value + myJHBuntRecruiters.numBusy
        myNumAtMalWartAtClosing.value = myMalWartRecruiters.waitingQ.numInQ.value + myMalWartRecruiters.numBusy
        myNumAtConversationAtClosing.value = myNumInConversationArea.value
        myNumInSystemAtClosing.value = myNumInSystem.value
    }

    private fun decideRecruiter(): ResourceWithQ {
        // check the equal case first to show no preference
        val j = myJHBuntRecruiters.waitingQ.size + myJHBuntRecruiters.numBusy
        val m = myMalWartRecruiters.waitingQ.size + myMalWartRecruiters.numBusy
        if (j == m ){
            if (myDecideRecruiter.value.toBoolean()) {
                return myJHBuntRecruiters
            } else {
                return myMalWartRecruiters
            }
        } else if (j < m) {
            return myJHBuntRecruiters
        } else  {
            // MalWart must be smaller
            return myMalWartRecruiters
        }
    }

    private inner class Student : Entity() {
        val isMixer = myDecideToMix.value.toBoolean()
        val isLeaver = myDecideToLeave.value.toBoolean()

        val mixingStudentProcess = process(addToSequence = false) {
            myNumInSystem.increment()
            delay(walkToNameTags)
            // at name tag station
            if (isClosed) {
                // mixture closed during walking
                delay(walkFromNameTagsToExit)
                departMixer(this@Student)
            } else {
                // get name tags
                delay(myNameTagTimeRV)
                if (isClosed) {
                    // mixture closed during name tag
                    delay(walkFromNameTagsToExit)
                    departMixer(this@Student)
                } else {
                    // goto the conversation area
                    delay(walkFromNameTagsToConversationArea)
                    if (isClosed) {
                        // closed during walking, must leave
                        delay(walkFromConversationAreaToExit)
                        departMixer(this@Student)
                    } else {
                        // start the conversation
                        myNumInConversationArea.increment()
                        delay(myInteractionTimeRV)
                        myNumInConversationArea.decrement()
                        if (isClosed) {
                            // closed during conversation, must leave
                            delay(walkFromConversationAreaToExit)
                            departMixer(this@Student)
                        } else {
                            // decide to leave or go to recruiting
                            if (isLeaver) {
                                delay(walkFromConversationAreaToExit)
                                departMixer(this@Student)
                            } else {
                                delay(walkFromConversationAreaToRecruiting)
                                if (!isClosed) {
                                    // proceed with recruiting visit
                                    val firstRecruiter = decideRecruiter()
                                    if (firstRecruiter == myJHBuntRecruiters) {
                                        use(myJHBuntRecruiters, delayDuration = myTalkWithJHBunt)
                                        use(myMalWartRecruiters, delayDuration = myTalkWithMalMart)
                                    } else {
                                        use(myMalWartRecruiters, delayDuration = myTalkWithMalMart)
                                        use(myJHBuntRecruiters, delayDuration = myTalkWithJHBunt)
                                    }
                                }
                                // either closed or they visited recruiting
                                delay(walkFromRecruitingToExit)
                                departMixer(this@Student)
                            }
                        }
                    }
                }
            }
        }

        val recruitingOnlyStudentProcess = process(addToSequence = false) {
            myNumInSystem.increment()
            delay(walkToNameTags)
            // at name tag station
            if (isClosed) {
                // mixture closed during walking
                delay(walkFromNameTagsToExit)
                departMixer(this@Student)
            } else {
                delay(myNameTagTimeRV)
                if (isClosed) {
                    // mixture closed during name tag
                    delay(walkFromNameTagsToExit)
                    departMixer(this@Student)
                } else {
                    // proceed to recruiting
                    delay(walkFromNameTagsToRecruiting)
                    if (!isClosed) {
                        // proceed with recruiting visit
                        val firstRecruiter = decideRecruiter()
                        if (firstRecruiter == myJHBuntRecruiters) {
                            use(myJHBuntRecruiters, delayDuration = myTalkWithJHBunt)
                            use(myMalWartRecruiters, delayDuration = myTalkWithMalMart)
                        } else {
                            use(myMalWartRecruiters, delayDuration = myTalkWithMalMart)
                            use(myJHBuntRecruiters, delayDuration = myTalkWithJHBunt)
                        }
                    }
                    // either closed or they visited recruiting
                    delay(walkFromRecruitingToExit)
                    departMixer(this@Student)
                }
            }
        }

        private fun departMixer(departingStudent: Student) {
            myNumInSystem.decrement()
            val st = time - departingStudent.createTime
            myOverallSystemTime.value = st
            if (isMixer) {
                myMixingStudentSystemTime.value = st
                if (isLeaver) {
                    myMixingAndLeavingSystemTime.value = st
                }
            } else {
                myRecruitingOnlySystemTime.value = st
            }

        }
    }
}

fun main() {
    val m = Model()
    StemFairMixerEnhanced(m, "Stem Fair Enhanced")
    m.lengthOfReplication = 6.0 * 60.0
    m.numberOfReplications = 400
    m.simulate()
    m.print()
}