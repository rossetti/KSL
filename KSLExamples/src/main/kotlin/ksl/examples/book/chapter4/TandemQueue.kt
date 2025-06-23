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

package ksl.examples.book.chapter4

import ksl.modeling.elements.EventGenerator
import ksl.modeling.elements.EventGeneratorIfc
import ksl.modeling.station.*
import ksl.modeling.variable.*
import ksl.simulation.ModelElement

class TandemQueue(
    parent: ModelElement,
    name: String? = null
) : ModelElement(parent, name) {

    private val myNS: TWResponse = TWResponse(parent = this, name = "${this.name}:NS")
    val numInSystem: TWResponseCIfc
        get() = myNS

    private val mySysTime: Response = Response(parent = this, name = "${this.name}:TotalSystemTime")
    val totalSystemTime: ResponseCIfc
        get() = mySysTime

    private val myNumProcessed: Counter = Counter(parent = this, name = "${this.name}:TotalProcessed")
    val totalProcessed: CounterCIfc
        get() = myNumProcessed

    private val ad = ExponentialRV(6.0, 1)
    private val myArrivalGenerator: EventGenerator = EventGenerator(
        parent = this,
        generateAction = this::arrivalEvent, timeUntilFirstRV = ad, timeBtwEventsRV = ad
    )

    private val myStation1: SingleQStation = SingleQStation(
        parent = this,
        activityTime = ExponentialRV(4.0, 2),
        name = "${this.name}:Station1"
    )
    val station1: SingleQStationCIfc
        get() = myStation1

    private val myStation2: SingleQStation = SingleQStation(
        parent = this,
        activityTime = ExponentialRV(3.0, 3),
        name = "${this.name}:Station2"
    )
    val station2: SingleQStationCIfc
        get() = myStation2

    init {
        myStation1.nextReceiver(myStation2)
        myStation2.nextReceiver(ExitSystem())
    }

    private fun arrivalEvent(generator: EventGeneratorIfc) {
        val customer = QObject()
        myNS.increment()
        myStation1.receive(customer)
    }

    private inner class ExitSystem : QObjectReceiverIfc {
        override fun receive(arrivingQObject: QObject) {
            mySysTime.value = time - arrivingQObject.createTime
            myNumProcessed.increment()
            myNS.decrement()
        }
    }
}