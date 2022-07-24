/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package ksl.utilities.statistic

import ksl.utilities.GetValueIfc
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.observers.DoubleEmitter
import ksl.utilities.observers.DoubleEmitterIfc
import ksl.utilities.observers.Observable
import ksl.utilities.observers.ObservableIfc
import ksl.utilities.random.rvariable.NormalRV

private var counter: Int = 0

/**  Continually gets the value of the supplied GetValueIfc in the run() until
 * the supplied sampling half-width requirement is met or the default maximum
 * number of iterations is reached, whichever comes first.
 *
 * @author rossetti
 */
class HalfWidthSequentialSampler(aName: String? = null) : IdentityIfc by Identity(aName),
    ObservableIfc<Double> by Observable(),
    DoubleEmitterIfc by DoubleEmitter() {

    private var myStatistic: Statistic = Statistic(name)

    var desiredHalfWidth: Double = 0.01
        set(hw) {
            require(hw > 0.0) { "The desired half-width must be > 0" }
            field = hw
        }

    var maxIterations: Long = 100000
        set(maxIter) {
            require(maxIter > 1) { "The maximum number of iterations must be > 1" }
            field = maxIter
        }

    var confidenceLevel: Double
        get() {
            return myStatistic.confidenceLevel
        }
        set(level) {
            myStatistic.confidenceLevel = level
        }

    fun hasConverged(dhw: Double): Boolean {
        return (myStatistic.halfWidth > 0.0) && (myStatistic.halfWidth <= dhw)
    }

    val statistic: StatisticIfc
        get() = myStatistic.instance()

    fun run(v: GetValueIfc, dhw: Double = desiredHalfWidth, maxIter: Long = maxIterations): Boolean {
        myStatistic.reset()
        desiredHalfWidth = dhw
        maxIterations = maxIter
        do {
            val x = v.value()
            notifyObservers(this, x)
            myStatistic.collect(x)
            if (myStatistic.count > 1) {
                if (hasConverged(dhw)) {
                    return true
                }
            }
        } while (myStatistic.count < maxIter)
        return false
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("HalfWidthSequentialSampler(desiredHalfWidth=$desiredHalfWidth, maxIterations=$maxIterations)")
        sb.appendLine()
        sb.append("converged? = ").append(hasConverged(desiredHalfWidth))
        sb.appendLine()
        sb.append(myStatistic.toString())
        return sb.toString()
    }
}

fun main() {
    val sampler = HalfWidthSequentialSampler()
    val rv = NormalRV()
    val converged = sampler.run(rv, 0.001)
    if (converged) {
        println("Half-width criteria was met.")
    } else {
        println("Half-width criteria was not met")
    }
    println()
    println(sampler)
}