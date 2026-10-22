/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.simulation.Model
import ksl.simulation.ModelElement

/**
 *  Example 6.8
 *  This example illustrates how to use the blockUntilAllCompleted() suspending function.
 */
fun main() {
    val m = Model()
    BlockUntilAllCompletedExample(m)
    m.numberOfReplications = 1
    m.lengthOfReplication = 100.0
    m.simulate()
}

/**
 * Example 6.8 — demonstrates blockUntilAllCompleted(). An order consists of three parts. The order
 * activates the production process of every part, so that the parts proceed in parallel on two
 * machines, and then blocks until all the part processes have completed. The order completes
 * when its slowest part completes.
 */
class BlockUntilAllCompletedExample(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name) {

    private val machines: ResourceWithQ = ResourceWithQ(this, "Machines", capacity = 2)

    private inner class Order(processingTimes: List<Double>) : Entity() {
        private val parts = processingTimes.map { Part(this, it) }

        val fulfillment: KSLProcess = process("fulfillment") {
            println("$time > order ${this@Order.name} arrives with ${parts.size} parts")
            val partProcesses = mutableSetOf<KSLProcess>()
            for (part in parts) {
                activate(part.production)
                partProcesses.add(part.production)
            }
            println("$time > order ${this@Order.name} blocks until all of its parts are completed")
            blockUntilAllCompleted(partProcesses)
            println("$time > order ${this@Order.name} is completed, time in system = ${time - createTime}")
        }
    }

    private inner class Part(
        private val order: Order,
        private val processingTime: Double
    ) : Entity() {
        val production: KSLProcess = process("production") {
            println("$time > part ${this@Part.name} of order ${order.name} waits for a machine")
            use(machines, delayDuration = processingTime)
            println("$time > part ${this@Part.name} of order ${order.name} is completed")
        }
    }

    override fun initialize() {
        activate(Order(listOf(10.0, 15.0, 20.0)).fulfillment)
    }
}
