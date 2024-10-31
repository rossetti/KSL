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

import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ResourceWithQ
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement

class TestProcessActivation(parent: ModelElement) : ProcessModel(parent, null) {

    val resource: ResourceWithQ = ResourceWithQ(this, "R1")

    private inner class Customer: Entity() {
        val process1 : KSLProcess = process("process1") {
            println("time = $time before the first delay in ${this@Customer}")
            delay(10.0)
            println("time = $time after the first delay in ${this@Customer}")
            println("time = $time before the second delay in ${this@Customer}")
            delay(20.0)
            println("time = $time after the second delay in ${this@Customer}")
            println("time = $time scheduling process 2 in ${this@Customer}")
       //     activate(this@Customer.process2)  // activate is not allowed because current process is running
            // scheduling will work only if current process completes before activation event
            // safer to use afterRunningProcess() or determineNextProcess() which are called after current process has completed
            schedule(this@Customer::activateProcess2Event, 0.0)
        }

        val process2: KSLProcess = process("process2"){
            println("time = $time starting process 2 ${this@Customer}")
            val a  = seize(resource)
            delay(10.0)
            release(a)
        }

        private fun activateProcess2Event(event: KSLEvent<Nothing>){
            println("time = $time activating process 2}")
            activate(this.process2)
        }

        override fun afterRunningProcess(completedProcess: KSLProcess) {
            // this is called after any process completes, thus need to check which completed
//            if (completedProcess == process1) {
//                schedule(this@Customer::activateProcess2Event, 0.0)
//            }
        }

        // could also override this fun to determine the next
        override fun determineNextProcess(completedProcess: KSLProcess): KSLProcess? {
            return super.determineNextProcess(completedProcess)
        }

    }


    //   private var customer: Customer? = null

    override fun initialize() {
        val e = Customer()
        activate(e.process1)
    }

}

fun main(){
    val m = Model()
    val test = TestProcessActivation(m)

    m.lengthOfReplication = 100.0
    m.numberOfReplications = 1
    m.simulate()
}