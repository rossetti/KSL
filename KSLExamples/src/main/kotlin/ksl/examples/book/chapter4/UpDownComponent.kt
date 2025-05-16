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
package ksl.examples.book.chapter4

import ksl.modeling.variable.Counter
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVariableIfc


/**
 * An UpDownComponent is a model element that has two states UP = 1 and DOWN = 0.
 * This class models the random time spent in the up and down states and collects
 * statistics on the number of failures (down events), the average time spent in
 * the up state, and the average length of the cycles. Two events and their
 * actions are defined to model the up state change and the down state change.
 */
class UpDownComponent (parent: ModelElement, name: String? = null) :
    ModelElement(parent, name) {
    private val myUpTime: RandomVariable
    private val myDownTime: RandomVariable
    private val myState: TWResponse
    private val myCycleLength: Response
    private val myCountFailures: Counter
    private val myUpChangeAction = UpChangeAction()
    private val myDownChangeAction = DownChangeAction()
    private var myTimeLastUp = 0.0

    init {
        val utd: RVariableIfc = ExponentialRV(1.0, streamNum = 1)
        val dtd: RVariableIfc = ExponentialRV(2.0, streamNum = 2)
        myUpTime = RandomVariable(this, utd, "up time")
        myDownTime = RandomVariable(this, dtd, "down time")
        myState = TWResponse(this, name = "state")
        myCycleLength = Response(this, name = "cycle length")
        myCountFailures = Counter(this, name = "count failures")
    }

    override fun initialize() {
        // assume that the component starts in the UP state at time 0.0
        myTimeLastUp = 0.0
        myState.value = UP
        // schedule the time that it goes down
        schedule(myDownChangeAction, myUpTime.value)
        //schedule(myDownChangeAction).name("Down").in(myUpTime).units();
    }

    private inner class UpChangeAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            // this event action represents what happens when the component goes up
            // record the cycle length, the time btw up states
            myCycleLength.value = time - myTimeLastUp
            // component has just gone up, change its state value
            myState.value = UP
            // record the time it went up
            myTimeLastUp = time
            // schedule the down state change after the uptime
            schedule(myDownChangeAction, myUpTime.value)
        }
    }

    private inner class DownChangeAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            // component has just gone down, change its state value
            myCountFailures.increment()
            myState.value = DOWN
            // schedule when it goes up after the downtime
            schedule(myUpChangeAction, myDownTime.value)
        }
    }

    companion object {
        const val UP = 1.0
        const val DOWN = 0.0
    }
}