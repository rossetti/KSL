/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.models.hospitalward

import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.LognormalRV

class HospitalWardV1(parent: ModelElement, name: String?) : ModelElement(parent, name) {

    val IDLE = 0.0
    val BUSY = 1.0
    val OPEN = 1.0
    val CLOSED = 0.0

    // define the random variables
    private val myNonOpPatientStayTime = RandomVariable(this, ExponentialRV(60.0))
    private val myPreOpStayTime = RandomVariable(this, ExponentialRV(24.0))
    private val myOperationTime = RandomVariable(this, LognormalRV(0.75, 0.25 * 0.25))
    private val myPostOpStayTime = RandomVariable(this, ExponentialRV(72.0))
    private val myOpRoomOpenTime = RandomVariable(this, ConstantRV(24.0))
    private val myOpRoomCloseTime = RandomVariable(this, ConstantRV(4.0))
    private val myNonOpPatientTBA = RandomVariable(this, ExponentialRV(12.0))
    private val myOpPatientTBA = RandomVariable(this, ExponentialRV(6.0))

    // define the responses and state variables
    private val myNonOpPatientQ = TWResponse(this, "NonOpPatientQ")
    private val myOpPatientQ = TWResponse(this, "OpPatientQ")
    private val myOpRoomQ = TWResponse(this, "OpRoomQ")
    private val myAvailableBeds = TWResponse(this, "Beds Available", 20.0)
    private val myNumBusyBeds = TWResponse(this, "Beds Busy")
    private val myORRoomOpenStatus = TWResponse(this, "OR-Open-Status", OPEN)
    private val myORRoomIdleStatus = TWResponse(this, "OR-Idle-Status", IDLE)

    // define the event actions
    private val myNonOperationPatientArrivalAction = NonOperationPatientArrivalAction()
    private val myNonOperationPatientEndOfStayAction = NonOperationPatientDepartureAction()
    private val myOperationPatientArrivalAction = OperationPatientArrivalAction()
    private val myEndOfPreOperationStayAction = EndOfPreOperationStayAction()
    private val myEndOfOperationAction = EndOfOperationAction()
    private val myEndOfPostOperationStayAction = EndOfPostOperationStayAction()
    private val myOpenOperatingRoomAction = OpenOperatingRoomAction()
    private val myCloseOperatingRoomAction = CloseOperatingRoomAction()

    override fun initialize() {
        schedule(myNonOperationPatientArrivalAction, myNonOpPatientTBA)
        schedule(myOperationPatientArrivalAction, myOpPatientTBA)
        schedule(myCloseOperatingRoomAction, myOpRoomOpenTime)
    }

    private inner class NonOperationPatientArrivalAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            if (myAvailableBeds.value > 0.0) {
                myAvailableBeds.decrement()
                myNumBusyBeds.increment()
                schedule(myNonOperationPatientEndOfStayAction, myNonOpPatientStayTime)
            } else {
                myNonOpPatientQ.increment()
            }
            schedule(myNonOperationPatientArrivalAction, myNonOpPatientTBA)
        }
    }

    private inner class NonOperationPatientDepartureAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            if (myNonOpPatientQ.value > 0.0) {
                myNonOpPatientQ.decrement()
                schedule(myNonOperationPatientEndOfStayAction, myNonOpPatientStayTime)
            } else if (myOpPatientQ.value > 0.0) {
                myOpPatientQ.decrement()
                schedule(myEndOfPreOperationStayAction, myPreOpStayTime)
            } else {
                myAvailableBeds.increment()
                myNumBusyBeds.decrement()
            }
        }
    }

    private inner class OperationPatientArrivalAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            if (myAvailableBeds.value > 0.0) {
                myAvailableBeds.decrement()
                myNumBusyBeds.increment()
                schedule(myEndOfPreOperationStayAction, myPreOpStayTime)
            } else {
                myOpPatientQ.increment()
            }
            schedule(myOperationPatientArrivalAction, myOpPatientTBA)
        }
    }

    private inner class EndOfPreOperationStayAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            if (myORRoomIdleStatus.value == IDLE && myORRoomOpenStatus.value == OPEN) {
                myORRoomIdleStatus.value = BUSY
                schedule(myEndOfOperationAction, myOperationTime)
            } else {
                myOpRoomQ.increment()
            }
        }
    }

    private inner class EndOfOperationAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            if (myOpRoomQ.value > 0.0 && myORRoomOpenStatus.value == OPEN) {
                myOpRoomQ.decrement()
                schedule(myEndOfOperationAction, myOperationTime)
            } else {
                myORRoomIdleStatus.value = IDLE
            }
            schedule(myEndOfPostOperationStayAction, myPostOpStayTime)
        }
    }

    private inner class EndOfPostOperationStayAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            if (myNonOpPatientQ.value > 0.0) {
                myNonOpPatientQ.decrement()
                schedule(myNonOperationPatientEndOfStayAction, myNonOpPatientStayTime)
            } else if (myOpPatientQ.value > 0.0) {
                myOpPatientQ.decrement()
                schedule(myEndOfPreOperationStayAction, myPreOpStayTime)
            } else {
                myAvailableBeds.increment()
                myNumBusyBeds.decrement()
            }
        }
    }

    private inner class OpenOperatingRoomAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            myORRoomOpenStatus.value = OPEN
            if (myORRoomIdleStatus.value == IDLE && myOpRoomQ.value > 0.0) {
                myOpRoomQ.decrement()
                myORRoomIdleStatus.value = BUSY
                schedule(myEndOfOperationAction, myOperationTime)
            }
            schedule(myCloseOperatingRoomAction, myOpRoomOpenTime)
        }
    }

    private inner class CloseOperatingRoomAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            myORRoomOpenStatus.value = CLOSED
            schedule(myOpenOperatingRoomAction, myOpRoomCloseTime)
        }
    }

}

fun main() {
    // create the containing model
    val m: Model = Model()

    // create the model element and attach it to the main model
    HospitalWardV1(m, "HospitalWard")
    // set the parameters of the experiment
    m.lengthOfReplication = 11000.0
    m.numberOfReplications = 30

    m.simulate()

    m.print()
}
