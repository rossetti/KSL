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
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.examples.general.models.hospitalward.HospitalWard.OpPatient
import ksl.examples.general.models.hospitalward.HospitalWard.NoOpPatient

class BedWard(private val myHospitalWard: HospitalWard, name: String? = null) :
    ModelElement(myHospitalWard, name) {

    private val myAvailableBeds = TWResponse(this, "Beds Available", 20.0)
    private val myNumBusyBeds = TWResponse(this, "Beds Busy")

    private val myNoOpPatientQ = Queue<NoOpPatient>(this, "No Op Patient Q")
    private val myOpPatientQ = Queue<OpPatient>(this, "Op Patient Q")

    internal fun receiveNewPatient(p: NoOpPatient) {
        myNoOpPatientQ.enqueue(p)
        if (myAvailableBeds.value > 0.0) {
            if (p == myNoOpPatientQ.peekNext()) {
                myNoOpPatientQ.removeNext()
                myAvailableBeds.decrement()
                myNumBusyBeds.increment()
                schedule(this::endNoOperationPatientStay, p.hospitalStayTime, p)
            }
        }
    }

    internal fun receiveNewPatient(p: OpPatient) {
        myOpPatientQ.enqueue(p)
        if (myAvailableBeds.value > 0.0) {
            if (p == myOpPatientQ.peekNext()) {
                myOpPatientQ.removeNext()
                myAvailableBeds.decrement()
                myNumBusyBeds.increment()
                schedule(this::endOperationPatientPreOpStay, p.preOperationTime, p)
            }
        }
    }

    internal fun receivePostOperationPatient(p: OpPatient) {
        schedule(this::endOfPostOperationStay, p.postOperationTime, p)
    }

    private fun reallocateBed() {
        // preference by order of checking
        if (myNoOpPatientQ.isNotEmpty) {
            val p = myNoOpPatientQ.removeNext()!!
            schedule(this::endNoOperationPatientStay, p.hospitalStayTime, p)
        } else if (myOpPatientQ.isNotEmpty) {
            val p = myOpPatientQ.removeNext()!!
            schedule(this::endOperationPatientPreOpStay, p.preOperationTime, p)
        } else {
            myAvailableBeds.increment()
            myNumBusyBeds.decrement()
        }
    }

    private fun endNoOperationPatientStay(event: KSLEvent<NoOpPatient>){
        reallocateBed()
        val p = event.message!!
        myHospitalWard.departingPatient(p)
    }

    private fun endOperationPatientPreOpStay(event: KSLEvent<OpPatient>){
        val p = event.message!!
        myHospitalWard.sendToOperatingRoom(p)
    }

    private fun endOfPostOperationStay(event: KSLEvent<OpPatient>){
        reallocateBed()
        val p = event.message!!
        myHospitalWard.departingPatient(p)
    }
}