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

import ksl.modeling.variable.Counter
import ksl.modeling.variable.RandomVariable
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV


/**
 * A simple class to illustrate how to simulate a Poisson process.
 * The class uses an inner class that extends the EventAction
 * class to provide a callback for the events associated with
 * the Poisson process.  An instance of RandomVariable is used to hold
 * the exponential random variable and a Counter is used to collect
 * statistics on the number of arrivals.
 *
 * @author rossetti
 */
class SimplePoissonProcess (parent: ModelElement, name: String? = null) :
    ModelElement(parent, name) {
    private val myTBE: RandomVariable = RandomVariable(this, ExponentialRV(1.0))
    private val myCount: Counter = Counter(this, name = "Counts events")
    private val myEventHandler: EventHandler = EventHandler()

    override fun initialize() {
        super.initialize()
        schedule(myEventHandler, myTBE.value)
    }

    private inner class EventHandler : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            myCount.increment()
            schedule(myEventHandler, myTBE.value)
        }
    }
}

/**
 * Same as SimplePoissonProcess except that it illustrates that because EventActionIfc is a
 * functional interface, we can use a method handle the matches the functional interface to
 * schedule actions for the events. Just a differen way to schedule events
 */
class SimplePoissonProcessV2 (parent: ModelElement, name: String? = null) :
    ModelElement(parent, name) {
    private val myTBE: RandomVariable = RandomVariable(this, ExponentialRV(1.0))
    private val myCount: Counter = Counter(this, name = "Counts events")

    override fun initialize() {
        super.initialize()
        schedule(::arrival, myTBE)
    }

    private fun arrival(event: KSLEvent<Nothing>){
        myCount.increment()
        schedule(::arrival, myTBE)
    }
}