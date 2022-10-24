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

package examplepkg

import ksl.modeling.entity.*
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement

class TestBlockingQ(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {

//    val blockingQ: BlockingQueue<QObject> = BlockingQueue(this)
    val blockingQ: BlockingQueue<QObject> = BlockingQueue(this, capacity = 10)
    init {
//        blockingQ.waitTimeStatisticsOption(false)
    }
    private inner class Receiver: Entity() {
        val receiving : KSLProcess = process("receiving") {
            for (i in 1..15) {
                println("time = $time before the first delay in ${this@Receiver}")
                delay(1.0)
                println("time = $time trying to get item")
                waitForItems(blockingQ, 1)
                println("time = $time after getting item")
                delay(5.0)
                println("time = $time after the second delay in ${this@Receiver}")
            }
            println("time = $time exiting the process in ${this@Receiver}")
        }
    }

    private inner class Sender: Entity() {
        val sending : KSLProcess = process("sending") {
            for (i in 1..15){
                delay(5.0)
                println("time = $time after the first delay in ${this@Sender}")
                val item = QObject()
                println("time = $time before sending an item")
                send(item, blockingQ)
                println("time = $time after sending an item")
            }
            println("time = $time exiting the process in ${this@Sender}")
        }
    }

    override fun initialize() {
        val r = Receiver()
        activate(r.receiving)
        val s = Sender()
        activate(s.sending)
    }

}

fun main(){
    val m = Model()
    val test = TestBlockingQ(m)

    m.lengthOfReplication = 100.0
    m.numberOfReplications = 2
    m.simulate()
    m.print()
}