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

import ksl.simulation.JSLEvent
import ksl.simulation.ModelElement

/**
 * @author rossetti
 */
class SchedulingEventExamples (parent: ModelElement, name: String? = null) :
    ModelElement(parent, name) {
    private val myEventActionOne: EventActionOne
    private val myEventActionTwo: EventActionTwo

    init {
        myEventActionOne = EventActionOne()
        myEventActionTwo = EventActionTwo()
    }

    override fun initialize() {
        // schedule a type 1 event at time 10.0
        schedule(myEventActionOne, 10.0)
        // schedule an event that uses myEventAction for time 20.0
        schedule(myEventActionTwo, 20.0)
    }

    private inner class EventActionOne : EventAction<Nothing>() {
        override fun action(event: JSLEvent<Nothing>) {
            println("EventActionOne at time : $time")
        }
    }

    private inner class EventActionTwo : EventAction<Nothing>() {
        override fun action(event: JSLEvent<Nothing>) {
            //TODO why is Object needed?
            println("EventActionTwo at time : $time")
            // schedule a type 1 event for time t + 15
            schedule(myEventActionOne, 15.0)
            // reschedule the EventAction event for t + 20
            schedule(myEventActionTwo, 20.0)
        }
    }
}