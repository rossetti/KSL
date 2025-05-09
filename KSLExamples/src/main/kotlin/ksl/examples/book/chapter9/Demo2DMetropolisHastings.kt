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
package ksl.examples.book.chapter9

import ksl.utilities.Interval
import ksl.utilities.distributions.GeneralizedBeta
import ksl.utilities.io.KSL
import ksl.utilities.observers.ObserverIfc
import ksl.utilities.random.mcmc.*
import ksl.utilities.random.rvariable.UniformRV
import ksl.utilities.statistic.StatisticXY


fun main() {
 //      independencePFMH()

    randomWalkPFMH()
}

fun independencePFMH() {
    val f = Function
    val q = ExampleIndependencePF
    val x0 = doubleArrayOf(1.0, 27.5)
    val m = MetropolisHastingsMV(x0, f, q)
    m.runWarmUpPeriod(10000)
    m.attachObserver(WriteData("IndData.csv"))
    m.runAll(10000)
    println(m)
    println()
}

fun randomWalkPFMH() {
    val f = Function
    val q = ExampleRandomWalkPF
    val x0 = doubleArrayOf(1.0, 27.5)
    val m = MetropolisHastingsMV(x0, f, q)
    m.runWarmUpPeriod(10000)
    m.attachObserver(WriteData("RWData.csv"))
    m.runAll(10000)
    println(m)
    println()
}

/**
 *  This example function has the following derivable properties.
 *  E(X_1) = 1.0, sd(X_1) = 0.23
 *  E(X_2) = 27.36, sd(X_2) = 4.27
 */
object Function : FunctionMVIfc {
    override val dimension: Int
        get() = 2

    override fun f(x: DoubleArray): Double {
        val x0p = 17.0 * (x[0] - 1.0) * (x[0] - 1.0) / 50.0
        val x1p = (x[1] - 25.0) * (x[1] - 25.0) / 10000.0
        return (39.0 / 400.0) - x0p - x1p
    }
}

object ExampleIndependencePF : ProposalFunctionMVIfc {

    private val myY1Dist = GeneralizedBeta(alphaShape = 2.0, betaShape = 5.0, minimum = 0.5, maximum = 1.5)
    private val myY2Dist = GeneralizedBeta(alphaShape = 2.0, betaShape = 5.0, minimum = 20.0, maximum = 35.0)
    private val myY1RV = myY1Dist.randomVariable()
    private val myY2RV = myY2Dist.randomVariable()

    override val dimension: Int
        get() = 2

    override fun proposalRatio(currentX: DoubleArray, proposedY: DoubleArray): Double {
        //g(y|x) = g(x,y)
        // proposal ratio = g(x|y)/g(y|x) = g(y,x)/g(x,y)
        // for independent sampler the proposal ratio is g(x)/g(y)
        val gx = myY1Dist.pdf(currentX[0]) * myY2Dist.pdf(currentX[1])
        val gy = myY1Dist.pdf(proposedY[0]) * myY2Dist.pdf(proposedY[1])
        return (gx / gy)
    }

    override fun generateProposedGivenCurrent(currentX: DoubleArray): DoubleArray {
        return doubleArrayOf(myY1RV.value, myY2RV.value)
    }
}

class WriteData(fileName: String) : ObserverIfc<MetropolisHastingsMV> {
    var printWriter = KSL.createPrintWriter(fileName)
    override fun onChange(newValue: MetropolisHastingsMV) {
        printWriter.println(newValue.currentX().joinToString())
    }
}

object StatCollector : ObserverIfc<MetropolisHastingsMV> {
    var xyStats: StatisticXY = StatisticXY("X_1 and X_2 Stats")
    override fun onChange(newValue: MetropolisHastingsMV) {
        val x = newValue.currentX()
        xyStats.collectXY(x[0], x[1])
    }
}

object ExampleRandomWalkPF : ProposalFunctionMVIfc {
    private val y1Interval = Interval(0.5, 1.5)
    private val y2Interval = Interval(20.0, 35.0)
    private val y1c = 1.0
    private val y2c = 5.0
    private val e1rv = UniformRV(-y1c, y1c)
    private val e2rv = UniformRV(-y2c, y2c)

    override val dimension: Int
        get() = 2

    override fun proposalRatio(currentX: DoubleArray, proposedY: DoubleArray): Double {
        //g(y|x) = g(x|y) proposal ratio = g(x|y)/g(y|x) = 1.0
        return (1.0)
    }

    private fun genYGivenX(interval: Interval, rv: UniformRV, x: Double): Double {
        var yp: Double
        do {
            val e = rv.value
            yp = x + e
        } while (!interval.contains(yp))
        return yp
    }
    override fun generateProposedGivenCurrent(currentX: DoubleArray): DoubleArray {
        val y1 = genYGivenX(y1Interval, e1rv, currentX[0])
        val y2 = genYGivenX(y2Interval, e2rv, currentX[1])
        return doubleArrayOf(y1, y2)
    }
}

