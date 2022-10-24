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