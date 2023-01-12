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

package ksl.utilities.rootfinding

import ksl.utilities.Interval
import ksl.utilities.math.FunctionIfc
import ksl.utilities.math.KSLMath
import kotlin.math.abs

class BisectionRootFinder(
    func: FunctionIfc,
    interval: Interval,
    initialPoint: Double = (interval.lowerLimit + interval.upperLimit) / 2.0,
    maxIter: Int = 100,
    desiredPrec: Double = KSLMath.defaultNumericalPrecision
) : RootFinder(func, interval, initialPoint, maxIter, desiredPrec) {

    override fun evaluateIteration(): Double {
        result = (xPos + xNeg) * 0.5
        if (func.f(result) > 0) {
            xPos = result
        } else {
            xNeg = result
        }
        return relativePrecision(abs(xPos - xNeg))
    }

    override fun finalizeIterations() {
    }

    override fun initializeIterations() {
    }
}

fun main(){
    val f = FunctionIfc { x -> x * x * x + 4.0 * x * x - 10.0 }

    val b = BisectionRootFinder(f, Interval(1.0, 2.0))

    b.evaluate()

    println(b)
}