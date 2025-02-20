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

package ksl.examples.general.misc

import ksl.modeling.entity.*
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.IdentityIfc

class TestBlockUntilCompletion(parent: ModelElement) : ProcessModel(parent, null) {

    val resource: ResourceWithQ = ResourceWithQ(this, "R1")

    private inner class Entity1: Entity() {
        val process1 : KSLProcess = process("process1") {
//            val e2 = Entity2()
//            activate(e2.process2)
            println("time = $time before the first delay in ${this@Entity1}")
            delay(10.0)
            println("time = $time after the first delay in ${this@Entity1}")
            val e2 = Entity2()
            activate(e2.process2)
            println("time = $time ${this@Entity1.name} before blocking for process ${e2.process2.name}")
            blockUntilCompleted(e2.process2)
            println("time = $time ${this@Entity1.name} after blocking for process ${e2.process2.name}")
        }

        override fun afterRunningProcess(completedProcess: KSLProcess) {
            println("time = $time ${this@Entity1.name} afterRunningProcess: completed process = $completedProcess")
        }

        // could also override this fun to determine the next
        override fun determineNextProcess(completedProcess: KSLProcess): KSLProcess? {
            return super.determineNextProcess(completedProcess)
        }

        override fun handleTerminatedProcess(terminatedProcess: KSLProcess) {
            println("handleTerminatedProcess for ${this@Entity1.name}")
            super.handleTerminatedProcess(terminatedProcess)
        }

    }

    private inner class Entity2: Entity() {

        val process2: KSLProcess = process("process2"){
            println("time = $time starting process 2 ${this@Entity2.name}")
            val a  = seize(resource)
            delay(50.0)
      //      throw ProcessTerminatedException()
            release(a)
            println("time = $time ended process 2 ${this@Entity2.name}")
        }

        override fun afterRunningProcess(completedProcess: KSLProcess) {
            println("time = $time ${this@Entity2.name} afterRunningProcess: completed process = $completedProcess")
        }

        // could also override this fun to determine the next
        override fun determineNextProcess(completedProcess: KSLProcess): KSLProcess? {
            return super.determineNextProcess(completedProcess)
        }

        override fun handleTerminatedProcess(terminatedProcess: KSLProcess) {
            println("handleTerminatedProcess for ${this@Entity2.name}")
            super.handleTerminatedProcess(terminatedProcess)
        }

    }

    internal inner class CTransferEntity(
        nextConveyor: Conveyor,
        entryLocation: IdentityIfc,
        numCellsNeeded: Int = 1,
        requestPriority: Int = CONVEYOR_REQUEST_PRIORITY,
        requestResumePriority: Int = RESUME_PRIORITY,
        suspensionName: String? = null
    ) : Entity() {
        lateinit var transferRequest: Conveyor.ConveyorRequest
        val transferProcess = process(){
            val r = requestConveyor(nextConveyor,
                entryLocation, numCellsNeeded, requestPriority,
                requestResumePriority, suspensionName)
            transferRequest = r as Conveyor.ConveyorRequest
        }
    }


    //   private var customer: Customer? = null

    override fun initialize() {
        val e = Entity1()
        activate(e.process1)
    }

}

fun main(){
    val m = Model()
    val test = TestBlockUntilCompletion(m)

    m.lengthOfReplication = 100.0
    m.numberOfReplications = 1
    m.simulate()
}