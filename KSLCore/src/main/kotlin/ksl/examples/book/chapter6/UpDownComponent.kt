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
package ksl.examples.book.chapter6

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
        val utd: RVariableIfc = ExponentialRV(1.0)
        val dtd: RVariableIfc = ExponentialRV(2.0)
        myUpTime = RandomVariable(this, utd, "up time")
        myDownTime = RandomVariable(this, dtd, "down time")
        myState = TWResponse(this, name = "state")
        myCycleLength = Response(this, name = "cycle length")
        myCountFailures = Counter(this, name = "count failures")
    }

    public override fun initialize() {
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