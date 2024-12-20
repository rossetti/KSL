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
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.*

/**
 *  Example 6.9
 *  This example illustrates process view modeling for a simple production system that
 *  produces T-Shirts. A blocking queue is an important part of the implementation
 *  for this model.
 */
class TieDyeTShirts(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name) {

    private val myTBOrders: RVariableIfc = ExponentialRV(60.0)
    private val myType: RVariableIfc = DEmpiricalRV(doubleArrayOf(1.0, 2.0), doubleArrayOf(0.7, 1.0))
    private val mySize: RVariableIfc = DEmpiricalRV(doubleArrayOf(3.0, 5.0), doubleArrayOf(0.75, 1.0))
    private val myOrderSize = RandomVariable(this, mySize)
    private val myOrderType = RandomVariable(this, myType)
    private val myShirtMakingTime = RandomVariable(this, UniformRV(15.0, 25.0))
    private val myPaperWorkTime = RandomVariable(this, UniformRV(8.0, 10.0))
    private val myPackagingTime = RandomVariable(this, TriangularRV(5.0, 10.0, 15.0))

    private val mySystemTime = Response(this, "System Time")
    private val myNumInSystem = TWResponse(this, "Num in System")

    private val myShirtMakers: ResourceWithQ = ResourceWithQ(this, capacity = 2, name = "ShirtMakers_R")
    private val myOrderQ: RequestQ = RequestQ(this, name = "OrderQ")
    private val myPackager: ResourceWithQ = ResourceWithQ(this, "Packager_R")
    private val generator = EntityGenerator(::Order, myTBOrders, myTBOrders)
    private val completedShirtQ: BlockingQueue<Shirt> = BlockingQueue(this, name = "Completed Shirt Q")

    private inner class Order : Entity() {
        val type: Int = myOrderType.value.toInt() // in the problem, but not really used
        val size: Int = myOrderSize.value.toInt()
        var completedShirts: List<Shirt> = emptyList() // not really necessary

        val orderMaking: KSLProcess = process(isDefaultProcess = true) {
            myNumInSystem.increment()
            for (i in 1..size) {
                val shirt = Shirt(this@Order.id)
                activate(shirt.shirtMaking)
            }
            var a = seize(myPackager, queue = myOrderQ)
            delay(myPaperWorkTime)
            release(a)
            // wait for shirts
            completedShirts = waitForItems(completedShirtQ, size, { it.orderNum == this@Order.id })
            a = seize(myPackager)
            delay(myPackagingTime)
            release(a)
            myNumInSystem.decrement()
            mySystemTime.value = time - this@Order.createTime
        }

    }

    private inner class Shirt(val orderNum: Long) : Entity() {
        val shirtMaking: KSLProcess = process {
            val a = seize(myShirtMakers)
            delay(myShirtMakingTime)
            release(a)
            // send to orders
            send(this@Shirt, completedShirtQ)
        }
    }
}

fun main() {
    val m = Model()
    TieDyeTShirts(m, "Tie-Dye Shirts")
    m.lengthOfReplication = 480.0
    m.numberOfReplications = 30
    m.simulate()
    m.print()
}