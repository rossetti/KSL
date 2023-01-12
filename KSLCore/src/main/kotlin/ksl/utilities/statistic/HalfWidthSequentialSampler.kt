/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
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
package ksl.utilities.statistic

import ksl.utilities.GetValueIfc
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.observers.DoubleEmitter
import ksl.utilities.observers.DoubleEmitterIfc
import ksl.utilities.observers.Observable
import ksl.utilities.random.rvariable.NormalRV

private var counter: Int = 0

/**  Continually gets the value of the supplied GetValueIfc in the run() until
 * the supplied sampling half-width requirement is met or the default maximum
 * number of iterations is reached, whichever comes first.
 *
 * @author rossetti
 */
class HalfWidthSequentialSampler(aName: String? = null) : IdentityIfc by Identity(aName), Observable<Double>(),
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
            notifyObservers(x)
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