/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
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