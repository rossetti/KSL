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

import ksl.modeling.queue.Queue
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.examples.general.models.hospitalward.HospitalWard.OpPatient

class OperatingRoom(private val myHospitalWard: HospitalWard, name: String? = null) :
    ModelElement(myHospitalWard, name) {
    companion object {
        const val IDLE = 0.0
        const val BUSY = 1.0
        const val OPEN = 1.0
        const val CLOSED = 0.0
    }

    private val myOpRoomOpenTime = RandomVariable(this, ConstantRV(24.0))
    private val myOpRoomCloseTime = RandomVariable(this, ConstantRV(4.0))
    private val myORRoomOpenStatus = TWResponse(this, "OR-Open-Status", OPEN)
    private val myORRoomIdleStatus = TWResponse(this, "OR-Idle-Status", IDLE)
    private val myORQ: Queue<OpPatient> = Queue(this, "OR Q")

    private val myOpenOperatingRoomAction: OpenOperatingRoomAction = OpenOperatingRoomAction()
    private val myCloseOperatingRoomAction: CloseOperatingRoomAction = CloseOperatingRoomAction()
    private val myEndOfOperationAction: EndOfOperationAction = EndOfOperationAction()

    override fun initialize() {
        schedule(myCloseOperatingRoomAction, myOpRoomOpenTime)
    }

    internal fun receivePatient(p: OpPatient) {
        myORQ.enqueue(p)
        if (isIdle && isOpen) {
            if (p === myORQ.peekNext()) {
                myORRoomIdleStatus.value = BUSY
                myORQ.removeNext()
                schedule(myEndOfOperationAction, p.operationTime, p)
            }
        }
    }

    val isIdle: Boolean
        get() = myORRoomIdleStatus.value == IDLE
    val isOpen: Boolean
        get() = myORRoomOpenStatus.value == OPEN

    private inner class OpenOperatingRoomAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            myORRoomOpenStatus.value = OPEN
            if (isIdle && myORQ.isNotEmpty) {
                myORRoomIdleStatus.value = BUSY
                val p: OpPatient = myORQ.removeNext()!!
                schedule(myEndOfOperationAction, p.operationTime, p)
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

    private inner class EndOfOperationAction : EventAction<OpPatient>() {
        override fun action(event: KSLEvent<OpPatient>) {
            if (myORQ.isNotEmpty && isOpen) {
                val nextP: OpPatient = myORQ.removeNext()!!
                schedule(myEndOfOperationAction, nextP.operationTime, nextP)
            } else {
                myORRoomIdleStatus.value = IDLE
            }
            val currentP: OpPatient = event.message!!
            myHospitalWard.endOfOperation(currentP)
        }
    }

}