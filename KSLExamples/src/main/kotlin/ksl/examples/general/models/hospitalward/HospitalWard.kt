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

import ksl.modeling.elements.EventGenerator
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.LognormalRV

class HospitalWard(parent: ModelElement, name: String?) : ModelElement(parent, name) {

    private val myNonOpPatientStayTime = RandomVariable(this, ExponentialRV(60.0))
    private val myPreOpStayTime = RandomVariable(this, ExponentialRV(24.0))
    private val myOperationTime = RandomVariable(this, LognormalRV(0.75, 0.25 * 0.25))
    private val myPostOpStayTime = RandomVariable(this, ExponentialRV(72.0))

    private val myNonOpPatientTBA = ExponentialRV(12.0)
    private val myOpPatientTBA = ExponentialRV(6.0)

    private val mySystemTime = Response(this, "System Time")
    private val myBedWard: BedWard = BedWard(this)
    private val myOR: OperatingRoom = OperatingRoom(this)

    init {
        EventGenerator(this, this::noOperationPatientArrival, myNonOpPatientTBA, myNonOpPatientTBA)
        EventGenerator(this, this::operationPatientArrival, myOpPatientTBA, myOpPatientTBA)
    }

    internal fun departingPatient(p: QObject) {
        mySystemTime.value = time - p.createTime
    }

    internal fun sendToOperatingRoom(p: OpPatient) {
        myOR.receivePatient(p)
    }

    internal fun endOfOperation(p: OpPatient) {
        myBedWard.receivePostOperationPatient(p)
    }

    inner class NoOpPatient : QObject() {
        val hospitalStayTime: GetValueIfc
            get() = myNonOpPatientStayTime
    }

    inner class OpPatient : QObject() {
        val preOperationTime: GetValueIfc
            get() = myPreOpStayTime
        val operationTime: GetValueIfc
            get() = myOperationTime
        val postOperationTime: GetValueIfc
            get() = myPostOpStayTime
    }

    private fun noOperationPatientArrival(generator: EventGenerator){
        myBedWard.receiveNewPatient(NoOpPatient())
    }

    private fun operationPatientArrival(generator: EventGenerator){
        myBedWard.receiveNewPatient(OpPatient())
    }

}

fun main() {
    // create the containing model
    val m: Model = Model()

    // create the model element and attach it to the main model
    HospitalWard(m, "HospitalWard")
    // set the parameters of the experiment
    m.lengthOfReplication = 11000.0
    m.numberOfReplications = 30

    m.simulate()

    m.print()
}