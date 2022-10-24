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
/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package ksl.examples.book.chapter6

import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement

/**
 *  This example illustrates how to create a simulation model,
 *  attach a new model element, set the run length, and
 *  run the simulation. The example use the SchedulingEventExamples
 *  class to show how actions are used to implement events.
 * @author rossetti
 */
class SchedulingEventExamples (parent: ModelElement, name: String? = null) :
    ModelElement(parent, name) {
    private val myEventActionOne: EventActionOne = EventActionOne()
    private val myEventActionTwo: EventActionTwo = EventActionTwo()

    override fun initialize() {
        // schedule a type 1 event at time 10.0
        schedule(myEventActionOne, 10.0)
        // schedule an event that uses myEventAction for time 20.0
        schedule(myEventActionTwo, 20.0)
    }

    private inner class EventActionOne : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            println("EventActionOne at time : $time")
        }
    }

    private inner class EventActionTwo : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            println("EventActionTwo at time : $time")
            // schedule a type 1 event for time t + 15
            schedule(myEventActionOne, 15.0)
            // reschedule the EventAction event for t + 20
            schedule(myEventActionTwo, 20.0)
        }
    }
}