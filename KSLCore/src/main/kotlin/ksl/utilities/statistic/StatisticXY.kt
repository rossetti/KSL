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

import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.math.KSLMath
import kotlin.math.sqrt

/** A counter to count the number of created to assign "unique" ids
 */
private var myIdCounter_: Int = 0

/**
 *
 */
class StatisticXY(name: String? = "Statistic_${myIdCounter_ + 1}") : IdentityIfc by Identity(name) {

    // variables for collecting statistics
    private var avgx = 0.0
    private var avgy = 0.0
    private var varx = 0.0
    private var vary = 0.0
    private var sumxy = 0.0
    private var covxy = 0.0
    private var nxy = 0.0

    fun instance(): StatisticXY {
        val s = StatisticXY()
        s.avgx = avgx
        s.avgy = avgy
        s.covxy = covxy
        s.nxy = nxy
        s.varx = varx
        s.vary = vary
        return s
    }

    val averageX: Double
        get() = avgx
    val sumX: Double
        get() = avgx * count
    val averageY: Double
        get() = avgy
    val sumY: Double
        get() = avgy * count
    val varianceX: Double
        get() = varx
    val varianceY: Double
        get() = vary
    val sumXY: Double
        get() = sumxy
    val sumXX: Double
        get() = if (KSLMath.equal(count, 0.0)) {
            Double.NaN
        } else sumOfSquaredXX + sumX * averageX
    val sumYY: Double
        get() = if (KSLMath.equal(count, 0.0)) {
            Double.NaN
        } else sumOfSquaredYY + sumY * averageY
    val sumOfSquaredXX: Double
        get() = varx * count
    val sumOfSquaredYY: Double
        get() = vary * count
    val sumOfSquaredXY: Double
        get() = covxy * count

    /**
     *  Assuming a linear fit between X and Y, this returns
     *  the sum of squared error of the regression of the fit
     */
    val sumSquaredErrorsRegression: Double
        get() {
            val t = sumOfSquaredXY
            return t * t / sumOfSquaredXX
        }
    /**
     *  Assuming a linear fit between X and Y, this returns
     *  the sum of squared error total of the fit
     */
    val sumOfSquaresErrorTotal: Double //TODO check this
        get() = sumOfSquaredYY

    /**
     *  Assuming a linear fit between X and Y, this returns
     *  the coefficient of determination of the fit
     */
    val coeffOfDetermination: Double
        get() = sumSquaredErrorsRegression / sumOfSquaredYY

    /**
     *  Assuming a linear fit between X and Y, this returns
     *  the sum of squared error of the residuals of the fit
     */
    val sumSquareErrorResiduals: Double
        get() = sumOfSquaresErrorTotal - sumSquaredErrorsRegression

    /**
     *  Assuming a linear fit between X and Y, this returns
     *  the mean squared error of the residuals of the fit
     */
    val mseOfResiduals: Double
        get() {
            val n = count - 2.0
            return if (n <= 0.0) {
                Double.NaN
            } else sumSquareErrorResiduals / n
        }

    /**
     *  Assuming a linear fit between X and Y, this
     *  returns the estimated slope.
     */
    val slope: Double
        get() = if (KSLMath.equal(sumOfSquaredXX, 0.0)) {
            Double.NaN
        } else sumOfSquaredXY / sumOfSquaredXX

    /**
     *  Assuming a linear fit between X and y, this returns
     *  the std error of the estimated slope.
     */
    val slopeStdError: Double
        get() = sqrt(mseOfResiduals / sumOfSquaredXX)

    /**
     *  Assuming a linear fit between X and y, this
     *  returns the estimated intercept term
     */
    val intercept: Double
        get() = averageY - slope * averageX

    /**
     *  Assuming a linear fit between X and Y, this
     *  returns the std error of the intercept term
     */
    val interceptStdError: Double
        get() {
            val n = count
            val n2 = n - 2.0
            if (n2 <= 0.0) {
                return Double.NaN
            }
            val t1 = 1.0 / n + avgx * avgx / sumOfSquaredXX
            val t2 = sumSquareErrorResiduals / n2
            return sqrt(t1 * t2)
        }

    /**
     *  Assuming a linear fit between X and Y, this returns
     *  the adjusted R-squared value
     */
    val adjustedRSq: Double
        get() {
            val n = count
            val k = 1.0
            val r2 = coeffOfDetermination
            val d = n - 1.0 - k
            return if (d <= 0.0) {
                Double.NaN
            } else ((n - 1.0) * r2 - k) / d
        }

    /**
     *  The covariance between X and Y
     */
    val covarianceXY: Double
        get() = if (nxy > 1.0) {
            covxy
        } else {
            Double.NaN
        }

    /**
     *  The number of pairs presented to the collect methods
     */
    val count: Double
        get() = nxy

    /**
     *  The (Pearson) correlation between X and Y
     */
    val correlationXY: Double
        get() = if (nxy > 1.0) {
            covxy / sqrt(varx * vary) // matches Pearson correlation
        } else {
            Double.NaN
        }

    fun reset() {
        avgx = 0.0
        avgy = 0.0
        varx = 0.0
        vary = 0.0
        sumxy = 0.0
        covxy = 0.0
        nxy = 0.0
    }

    /**
     *  Convenience method for collecting over the arrays.
     *  The arrays must be of the same size.
     */
    fun collectXY(x: DoubleArray, y: DoubleArray){
        require(x.size == y.size) {"The array sizes must be equal"}
        for(i in x.indices){
            collectXY(x[i], y[i])
        }
    }

    /**
     *  Collects statistics on the presented pair
     */
    fun collectXY(x: Double, y: Double) {
        // compute for n+1
        val n1: Double = nxy + 1.0
        val r1: Double = nxy / n1
        val deltax = (avgx - x) / n1
        val deltay = (avgy - y) / n1
        sumxy = sumxy + x * y
        covxy = r1 * covxy + nxy * deltax * deltay
        avgx = avgx - deltax
        avgy = avgy - deltay
        varx = nxy * deltax * deltax + r1 * varx
        vary = nxy * deltay * deltay + r1 * vary
        nxy = nxy + 1.0
    }

    /**
     *  the average x divided by average y
     */
    val ratioXY: Double
        get() = if (avgy == 0.0) {
            if (avgx > 0.0) {
                Double.POSITIVE_INFINITY
            } else if (avgx < 0.0) {
                Double.NEGATIVE_INFINITY
            } else {
                Double.NaN
            }
        } else {
            avgx / avgy
        }

    /**
     *  the variance of the ratio of X over Y
     */
    val ratioXYVariance: Double
        get() {
            val mu = ratioXY
            val vx = varianceX
            val vy = varianceY
            val cxy = covarianceXY
            return vx - 2.0 * mu * cxy + mu * mu * vy
        }

    /**
     *  the std error of the ratio of X over Y
     */
    val ratioXYStdError: Double
        get() = if (nxy >= 1.0) {
            if (avgy != 0.0) {
                val sd = sqrt(ratioXYVariance)
                val se = sd / (avgy * nxy)
                se
            } else {
                Double.NaN
            }
        } else {
            Double.NaN
        }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("---------------")
        sb.appendLine()
        sb.append("X Y Statistics: ")
        sb.appendLine()
        sb.append("---------------")
        sb.appendLine()
        sb.append("nxy: ")
        sb.append(nxy)
        sb.appendLine()
        sb.append("avg x: ")
        sb.append(avgx)
        sb.appendLine()
        sb.append("sum x: ")
        sb.append(sumX)
        sb.appendLine()
        sb.append("avg y: ")
        sb.append(avgy)
        sb.appendLine()
        sb.append("sum y: ")
        sb.append(sumY)
        sb.appendLine()
        sb.append("var x: ")
        sb.append(varx)
        sb.appendLine()
        sb.append("var y: ")
        sb.append(vary)
        sb.appendLine()
        sb.append("covxy: ")
        sb.append(covxy)
        sb.appendLine()
        sb.append("corrxy: ")
        sb.append(correlationXY)
        sb.appendLine()
        sb.append("sum xy: ")
        sb.append(sumXY)
        sb.appendLine()
        sb.append("SSXY: ")
        sb.append(sumOfSquaredXY)
        sb.appendLine()
        sb.append("SSXX: ")
        sb.append(sumOfSquaredXX)
        sb.appendLine()
        sb.append("sum xx: ")
        sb.append(sumXX)
        sb.appendLine()
        sb.append("sum yy: ")
        sb.append(sumYY)
        sb.appendLine()
        sb.append("intercept: ")
        sb.append(intercept)
        sb.appendLine()
        sb.append("slope: ")
        sb.append(slope)
        sb.appendLine()
        sb.append("Sum Squared Error Regression: ")
        sb.append(sumSquaredErrorsRegression)
        sb.appendLine()
        sb.append("Sum Squared Error Residuals: ")
        sb.append(sumSquareErrorResiduals)
        sb.appendLine()
        sb.append("Sum Squared Error Total: ")
        sb.append(sumOfSquaresErrorTotal)
        sb.appendLine()
        sb.append("coefficient of determination: ")
        sb.append(coeffOfDetermination)
        sb.appendLine()
        sb.append("adjusted R Squared: ")
        sb.append(adjustedRSq)
        sb.appendLine()
        sb.append("MSE of Residuals: ")
        sb.append(mseOfResiduals)
        sb.appendLine()
        sb.append("Slope std error: ")
        sb.append(slopeStdError)
        sb.appendLine()
        sb.append("Intercept std error: ")
        sb.append(interceptStdError)
        sb.appendLine()
        sb.append("ratio of xy: ")
        sb.append(ratioXY)
        sb.appendLine()
        sb.append("var ratio of xy: ")
        sb.append(ratioXYVariance)
        sb.appendLine()
        sb.append("se ratio of xy: ")
        sb.append(ratioXYStdError)
        sb.appendLine()
        return sb.toString()
    }

    companion object {

        fun instance(stat: StatisticXY): StatisticXY {
            val s = StatisticXY()
            s.avgx = stat.avgx
            s.avgy = stat.avgy
            s.covxy = stat.covxy
            s.nxy = stat.nxy
            s.varx = stat.varx
            s.vary = stat.vary
            return s
        }
    }

    /**
     *
     */
    init {
        myIdCounter_ = myIdCounter_ + 1
        avgx = 0.0
        avgy = 0.0
        covxy = 0.0
        nxy = 0.0
        varx = 0.0
        vary = 0.0
    }
}

fun main() {
    test2()
}

fun test1() {
    val x = doubleArrayOf(0.0, 30.0, 10.0, 25.0, 12.0, 14.0, 5.0, 40.0, 42.0, 32.0, 16.0)
    val y = doubleArrayOf(0.0, 4.0, 2.0, 3.0, 2.0, 3.0, 1.0, 6.0, 7.0, 5.0, 3.0)
    val stat = StatisticXY()
    for (i in x.indices) {
        stat.collectXY(x[i], y[i])
    }
    println(stat)
}

fun test2() {
    val x = doubleArrayOf(
        99.0, 101.1, 102.7, 103.0, 105.4, 107.0, 108.7, 110.8,
        112.1, 112.4, 113.6, 113.8, 115.1, 115.4, 120.0
    )
    val y = doubleArrayOf(
        28.8, 27.9, 27.0, 25.2, 22.8, 21.5, 20.9, 19.6, 17.1,
        18.9, 16.0, 16.7, 13.0, 13.6, 10.8
    )
    val stat = StatisticXY()
    for (i in x.indices) {
        stat.collectXY(x[i], y[i])
    }
    println(stat)
}

fun test3() {
    val x = doubleArrayOf(
        12.0, 30.0, 36.0, 40.0, 45.0, 57.0, 62.0, 67.0, 71.0, 78.0, 93.0, 94.0, 100.0, 105.0
    )
    val y = doubleArrayOf(
        3.3, 3.2, 3.4, 3.0, 2.8, 2.9, 2.7, 2.6, 2.5, 2.6, 2.2, 2.0, 2.3, 2.1
    )
    val stat = StatisticXY()
    for (i in x.indices) {
        stat.collectXY(x[i], y[i])
    }
    println(stat)
}