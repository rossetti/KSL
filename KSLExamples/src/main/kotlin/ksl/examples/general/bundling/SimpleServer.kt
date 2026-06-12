/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.bundling

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.queue.Queue
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.RandomVariableCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * A small single-queue, c-server station — the worked example for the
 * **Preparing a Model for Bundling** guide (`docs/guides/ksl-model-bundling.md`).
 *
 * It is deliberately minimal: customers arrive, wait in one queue, are served by
 * one of [numServers] identical servers, and leave. The point of the example is
 * not the model itself but everything around it that makes a model *bundle-ready*:
 *
 *  - a **control** ([numServers]) that the desktop apps can set by name, and
 *  - clearly named **responses** that an author can nominate into a catalog.
 *
 * The bundle wiring lives in [SimpleServerBundle].
 */
class SimpleServer(
    parent: ModelElement,
    numServers: Int = 1,
    timeBetweenArrivals: RVariableIfc = ExponentialRV(1.0, streamNum = 1),
    serviceTime: RVariableIfc = ExponentialRV(0.7, streamNum = 2),
    name: String? = null
) : ModelElement(parent, name) {

    /**
     * Number of identical servers. Marking the **setter** with [KSLControl]
     * makes this an externally-settable parameter: the apps address it by the
     * key `"<elementName>.numServers"` and write to it before each run.
     */
    @set:KSLControl(controlType = ControlType.INTEGER, lowerBound = 1.0)
    var numServers: Int = numServers
        set(value) {
            require(value > 0) { "The number of servers must be > 0" }
            require(!model.isRunning) { "Cannot change the number of servers while the model is running" }
            field = value
        }

    private val myTBA: RandomVariable = RandomVariable(this, timeBetweenArrivals, name = "TimeBetweenArrivals")
    /** Interarrival-time source; its `mean` parameter is catalog-nominated. */
    val timeBetweenArrivals: RandomVariableCIfc get() = myTBA

    private val myServiceTime: RandomVariable = RandomVariable(this, serviceTime, name = "ServiceTime")
    /** Service-time source; its `mean` parameter is catalog-nominated. */
    val serviceTime: RandomVariableCIfc get() = myServiceTime

    private val myWaitingQ: Queue<QObject> = Queue(this, "WaitingQ")
    private val myNumBusy: TWResponse = TWResponse(this, "NumBusy")

    private val myNumInSystem: TWResponse = TWResponse(this, "NumInSystem")
    /** Time-average number of customers in the system. */
    val numInSystem: TWResponseCIfc get() = myNumInSystem

    private val mySystemTime: Response = Response(this, "SystemTime")
    /** Average time a customer spends in the system. */
    val systemTime: ResponseCIfc get() = mySystemTime

    private val myNumServed: Counter = Counter(this, "NumServed")
    /** Count of customers served. */
    val numServed: CounterCIfc get() = myNumServed

    override fun initialize() {
        schedule(this::arrival, myTBA)
    }

    private fun arrival(event: KSLEvent<Nothing>) {
        myNumInSystem.increment()
        val customer = QObject()
        myWaitingQ.enqueue(customer)
        if (myNumBusy.value < numServers) {
            myNumBusy.increment()
            val next = myWaitingQ.removeNext()
            schedule(this::endOfService, myServiceTime, next)
        }
        schedule(this::arrival, myTBA)
    }

    private fun endOfService(event: KSLEvent<QObject>) {
        myNumBusy.decrement()
        if (!myWaitingQ.isEmpty) {
            myNumBusy.increment()
            val next = myWaitingQ.removeNext()
            schedule(this::endOfService, myServiceTime, next)
        }
        val departing = event.message!!
        mySystemTime.value = time - departing.createTime
        myNumInSystem.decrement()
        myNumServed.increment()
    }
}
