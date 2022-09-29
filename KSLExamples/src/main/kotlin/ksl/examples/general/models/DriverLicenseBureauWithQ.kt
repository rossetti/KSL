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
package ksl.examples.general.models

import ksl.modeling.queue.Queue
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV


class DriverLicenseBureauWithQ(parent: ModelElement,
                               numberOfServers: Int = 1,
                               ad: RandomIfc = ExponentialRV(1.0, 1),
                               sd: RandomIfc = ExponentialRV(0.8, 2)) :
    ModelElement(parent) {
    var numServers = numberOfServers
        set(value) {
            require(value >=1){"The number of servers must be >= 1"}
            field = value
        }

    private val myWaitingQ: Queue<QObject> = Queue(this, "DriverLicenseQ")

    private val myServiceRV: RandomVariable = RandomVariable(this, sd)
    val serviceRV : RandomVariableCIfc
        get() = myServiceRV

    private val myArrivalRV: RandomVariable = RandomVariable(this, ad)
    val arrivalRV: RandomVariableCIfc
        get() = myArrivalRV

    private val myNumBusy: TWResponse = TWResponse(this,  name = "NumBusy")
    private val myNS: TWResponse  = TWResponse(this,  name = "NS")
    private val myNumServed: Counter = Counter(this, "Num Served")
    private val mySysTime: Response = Response(this, "System Time")
    private val myArrivalEventAction: ArrivalEventAction = ArrivalEventAction()
    private val myEndServiceEventAction: EndServiceEventAction = EndServiceEventAction()

    override fun initialize() {
        super.initialize()

        // start the arrivals
        schedule(myArrivalEventAction, myArrivalRV)
    }

    private inner class ArrivalEventAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            myNS.increment() // new customer arrived
            val arrival: QObject = QObject()
            myWaitingQ.enqueue(arrival) // enqueue the newly arriving customer
            if (myNumBusy.value < numServers) { // server available
                myNumBusy.increment() // make server busy
                val customer: QObject = myWaitingQ.removeNext()!! //remove the next customer
                //	schedule end of service, include the customer as the event's message
                schedule(myEndServiceEventAction, myServiceRV, customer)
            }
            //	always schedule the next arrival
            schedule(myArrivalEventAction, myArrivalRV)
        }
    }

    private inner class EndServiceEventAction : EventActionIfc<QObject> {
        override fun action(event: KSLEvent<QObject>) {
            val leavingCustomer: QObject = event.message!!
            mySysTime.value = (time - leavingCustomer.createTime)
            myNS.decrement() // customer departed
            myNumBusy.decrement() // customer is leaving server is freed
            myNumServed.increment()
            if (!myWaitingQ.isEmpty) { // queue is not empty
                val customer: QObject = myWaitingQ.removeNext()!! //remove the next customer
                myNumBusy.increment() // make server busy
                //	schedule end of service
                schedule(myEndServiceEventAction, myServiceRV, customer)
            }
        }
    }

}


fun runExperiment() {
    val model = Model("DLB_with_Q")
    // create the model element and attach it to the main model
    DriverLicenseBureauWithQ(model)
    model.numberOfReplications = 30
    model.lengthOfReplication = 20000.0
    model.lengthOfReplicationWarmUp = 5000.0
    // tell the simulation to run
    println("Simulation started.")
    model.simulate()
    println("Simulation completed.")
    model.print()
}


fun main() {
    runExperiment()
    println("Done!")
}