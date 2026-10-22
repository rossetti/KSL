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
 *  Example 6.7
 *  This example illustrates how to use the blockUntilCompleted() suspending function.
 */
fun main() {
    val m = Model()
    BlockUntilCompletedExample(m)
    m.numberOfReplications = 1
    m.lengthOfReplication = 100.0
    m.simulate()
}

/**
 * Example 6.7 — demonstrates blockUntilCompleted(). A customer drops off a car for an oil change,
 * which activates the car's service process, and then runs an errand. On returning, the customer
 * blocks until the car's service process has completed. The first customer returns before the
 * service is done and blocks; the second returns after the service is done and does not block.
 */
class BlockUntilCompletedExample(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name) {

    private val mechanic: ResourceWithQ = ResourceWithQ(this, "Mechanic")
    private val serviceTime = 30.0

    private inner class Customer(private val errandTime: Double) : Entity() {
        val visit: KSLProcess = process("visit") {
            val car = Car()
            println("$time > customer ${this@Customer.name} drops off car ${car.name}")
            activate(car.service)
            delay(errandTime)
            println("$time > customer ${this@Customer.name} returns: car service completed = ${car.service.isCompleted}")
            blockUntilCompleted(car.service)
            println("$time > customer ${this@Customer.name} leaves with car ${car.name}")
        }
    }

    private inner class Car : Entity() {
        val service: KSLProcess = process("service") {
            println("$time > car ${this@Car.name} waits for the mechanic")
            use(mechanic, delayDuration = serviceTime)
            println("$time > car ${this@Car.name} service completed")
        }
    }

    override fun initialize() {
        activate(Customer(errandTime = 20.0).visit)
        activate(Customer(errandTime = 70.0).visit, timeUntilActivation = 5.0)
    }
}
