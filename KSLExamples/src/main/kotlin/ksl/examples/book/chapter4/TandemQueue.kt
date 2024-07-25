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
import ksl.modeling.station.*
import ksl.modeling.variable.*
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV

class TandemQueue(
    parent: ModelElement,
    ad: RandomIfc = ExponentialRV(6.0, 1),
    sd1: RandomIfc = ExponentialRV(4.0, 2),
    sd2: RandomIfc = ExponentialRV(3.0, 3),
    name: String? = null
): ModelElement(parent, name) {

    private val myNS: TWResponse = TWResponse(this, "${this.name}:NS")
    val numInSystem: TWResponseCIfc
        get() = myNS

    private val mySysTime: Response = Response(this, "${this.name}:TotalSystemTime")
    val totalSystemTime: ResponseCIfc
        get() = mySysTime

    private val myNumProcessed: Counter = Counter(this, "${this.name}:TotalProcessed")
    val totalProcessed: CounterCIfc
        get() = myNumProcessed

    private val myArrivalGenerator: EventGenerator = EventGenerator(this,
        this::arrivalEvent, ad, ad)

    private val myStation1: SingleQStation = SingleQStation(this, sd1, name= "${this.name}:Station1")
    val station1: SingleQStationCIfc
        get() = myStation1

    private val myStation2: SingleQStation = SingleQStation(this, sd2, name= "${this.name}:Station2")
    val station2: SingleQStationCIfc
        get() = myStation2

    init {
        myStation1.nextReceiver = myStation2
        myStation2.nextReceiver = ExitSystem()
    }

    private fun arrivalEvent(generator: EventGenerator){
        val customer = QObject()
        myNS.increment()
        myStation1.receive(customer)
    }

    private inner class ExitSystem : QObjectReceiverIfc {
        override fun receive(qObject: QObject) {
            mySysTime.value = time - qObject.createTime
            myNumProcessed.increment()
            myNS.decrement()
        }
    }
}