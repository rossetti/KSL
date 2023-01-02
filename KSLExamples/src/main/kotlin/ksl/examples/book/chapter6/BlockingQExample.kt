/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.examples.book.chapter6

import ksl.modeling.entity.*
import ksl.simulation.Model
import ksl.simulation.ModelElement

class BlockingQExample(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {

//    val blockingQ: BlockingQueue<QObject> = BlockingQueue(this)
    val blockingQ: BlockingQueue<QObject> = BlockingQueue(this, capacity = 10)
    init {
//        blockingQ.waitTimeStatisticsOption(false)
    }
    private inner class Receiver: Entity() {
        val receiving : KSLProcess = process("receiving") {
            for (i in 1..15) {
                println("$time > before the first delay for receiving entity: ${this@Receiver.name}")
                delay(1.0)
                println("$time > trying to get item for receiving entity: ${this@Receiver.name}")
                waitForItems(blockingQ, 1)
                println("$time > after getting item for receiving entity: ${this@Receiver.name}")
                delay(5.0)
                println("$time > after the second delay for receiving entity: ${this@Receiver.name}")
            }
            println("$time > exiting the process of receiving entity: ${this@Receiver.name}")
        }
    }

    private inner class Sender: Entity() {
        val sending : KSLProcess = process("sending") {
            for (i in 1..15){
                delay(5.0)
                println("$time > after the first delay for sender ${this@Sender.name}")
                val item = QObject()
                println("$time > before sending an item from sender ${this@Sender.name}")
                send(item, blockingQ)
                println("$time > after sending an item from sender ${this@Sender.name}")
            }
            println("$time > exiting the process for sender ${this@Sender.name}")
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
    val test = BlockingQExample(m)

    m.lengthOfReplication = 100.0
    m.numberOfReplications = 1
    m.simulate()
    m.print()
}