/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

import ksl.utilities.distributions.Normal
import ksl.utilities.math.KSLMath
import ksl.utilities.random.rng.RandU01Ifc
import java.util.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

object U01Test {
    private val a = arrayOf(
        doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        doubleArrayOf(0.0, 4529.4, 9044.9, 13568.0, 18091.0, 22615.0, 27892.0),
        doubleArrayOf(0.0, 9044.9, 18097.0, 27139.0, 36187.0, 45234.0, 55789.0),
        doubleArrayOf(0.0, 13568.0, 27139.0, 40721.0, 54281.0, 67852.0, 83685.0),
        doubleArrayOf(0.0, 18091.0, 36187.0, 54281.0, 72414.0, 90470.0, 111580.0),
        doubleArrayOf(0.0, 22615.0, 45234.0, 67852.0, 90470.0, 113262.0, 139476.0),
        doubleArrayOf(0.0, 27892.0, 55789.0, 83685.0, 111580.0, 139476.0, 172860.0)
    )

    private val b = doubleArrayOf(0.0, 1.0 / 6.0, 5.0 / 24.0, 11.0 / 120.0, 19.0 / 720.0, 29.0 / 5040.0, 1.0 / 840.0)

    /** Use for large degrees of freedom
     *
     * @param df degrees of freedom
     * @param confidenceLevel the confidence level for the statistical test
     * @return the approximate chi-squared value
     */
    fun approxChiSQValue(df: Int, confidenceLevel: Double): Double {
        require(df > 0) { "The degrees of freedom must be > 0" }
        require(!(confidenceLevel <= 0 || confidenceLevel >= 1)) { "The confidence level must be (0,1)" }
        val z: Double = Normal.stdNormalInvCDF(1.0 - confidenceLevel)
        val t2 = 2.0 / (9 * df)
        val t1 = z * sqrt(t2)
        val t3 = 1.0 - t2 - t1
        return df * t3 * t3 * t3
    }

    /** Computes the chi-squared test statistic
     *
     * @param u the supposedly U(0,1) observations, must have at least 1 observation
     * @param k the number of intervals in the test, must have at least 1 interval
     * @return the test statistic
     */
    fun chiSquaredTestStatistic(u: DoubleArray, k: Int): Double {
        require(u.isNotEmpty()) { "The array of observations was empty" }
        require(k >= 1) { "Number of intervals = $k.  There must be at least 1 interval" }
        val n = u.size
        val f = DoubleArray(k + 1)
        // tabulate the frequencies
        for (i in 1..n) {
            val j = ceil(k * u[i - 1]).toInt()
            f[j] = f[j] + 1
        }
        val e = n.toDouble() / k.toDouble()
        var sum = 0.0
        for (j in 1..k) {
            sum = sum + (f[j] - e) * (f[j] - e)
        }
        sum = sum / e
        return sum
    }

    /** Computes the chi-squared test statistic with recommended intervals and array size
     *
     * @param u the supposedly U(0,1) observations, must have at least 15 observations
     * @param k the number of intervals in the test, must be less than or equal to u.size/5.0, and greater than
     * or equal to 3
     * @return the chi-squared test statistic
     */
    fun recommendedChiSquaredTestStatistic(u: DoubleArray, k: Int = floor(u.size / 5.0).toInt()): Double {
        require(k >= 3) { "The number of intervals must be >= 3 for approximately valid test" }
        require(u.size >= 15) { "The array must have at least 15 observations" }
        require(k <= (u.size / 5.0)) { "The number of intervals should be <= ${u.size / 5.0} to guarantee expected in intervals >= 5.0" }
        return chiSquaredTestStatistic(u, k)
    }

    /**
     *  Recommends the number of intervals for a chi-squared goodness of fit test of possible U(0,1) data based on the
     *  supplied sample size.  Adjusts the result of Williams approximation to ensure at 5 or more
     *  expected within the intervals and a minimum of at least 3 intervals.
     *
     *  On the Choice of the Number and Width of Classes for the Chi-Square Test of Goodness of Fit
     *  Author(s): C. Arthur Williams, Jr.
     * Source: Journal of the American Statistical Association , Mar., 1950, Vol. 45, No. 249 (Mar., 1950), pp. 77-86
     * Published by: Taylor & Francis, Ltd. on behalf of the American Statistical Association Stable
     * URL: http://www.jstor.com/stable/2280429
     *
     */
    fun recommendNumChiSquareU01Intervals(sampleSize: Int, confidenceLevel: Double = 0.95) : Int {
        require(sampleSize >= 15) { "The sample must have at least 15 observations" }
        val k1 = recommendNumChiSquaredIntervals(sampleSize, confidenceLevel)
        val k2 = floor(sampleSize/5.0).toInt()
        return 3.coerceAtLeast(min(k1, k2))
    }

    /**
     *  Recommends the number of intervals for a chi-squared goodness of fit test based on the
     *  supplied sample size.  This does not assume anything about the distribution.  An adjustment
     *  ensures that there are at least 3 intervals based on other theory.
     *
     *  On the Choice of the Number and Width of Classes for the Chi-Square Test of Goodness of Fit
     *  Author(s): C. Arthur Williams, Jr.
     * Source: Journal of the American Statistical Association , Mar., 1950, Vol. 45, No. 249 (Mar., 1950), pp. 77-86
     * Published by: Taylor & Francis, Ltd. on behalf of the American Statistical Association Stable
     * URL: http://www.jstor.com/stable/2280429
     */
    fun recommendNumChiSquaredIntervals(sampleSize: Int, confidenceLevel: Double = 0.95): Int {
        require(sampleSize >= 1) { "The sample size must be >=1" }
        require((0 < confidenceLevel) && (confidenceLevel < 1.0)) { "The confidence level must be in (0.0, 1.0)" }
        val c = Normal.stdNormalInvCDF(confidenceLevel)
        val x = (2.0 * (sampleSize - 1.0) * (sampleSize - 1.0)) / (c * c)
        val k = floor(4.0 * KSLMath.kthRoot(x, 5))
        return 3.coerceAtLeast(k.toInt())
    }

    /** Computes the chi-squared test statistic
     *
     * @param rng the thing that produces U(0,1) numbers, must not null
     * @param n number of random numbers to test
     * @param k the number of intervals in the test
     * @return the chi-squared test statistic
     */
    fun chiSquared1DTestStatistic(rng: RandU01Ifc, n: Long, k: Int): Double {
        require(n >= 1) { "The number of random numbers was <= 0" }
        require(k >= 1) { "The number of intervals was < 1" }
        val f = DoubleArray(k + 1)
        // tabulate the frequencies
        for (i in 1..n) {
            val u: Double = rng.randU01()
            val j = ceil(k * u).toInt()
            f[j] = f[j] + 1
        }
        var sum = 0.0
        val e = n.toDouble() / k.toDouble()
        for (j in 1..k) {
            sum = sum + (f[j] - e) * (f[j] - e)
        }
        sum = sum / e
        return sum
    }

    /** Performs the 2-D chi-squared serial test
     *
     * @param rng the thing that produces U(0,1) numbers, must not null
     * @param n number of random numbers to test
     * @param k the number of intervals in the test for each dimension
     * @return the chi-squared test statistic
     */
    fun chiSquaredSerial2DTestStatistic(rng: RandU01Ifc, n: Long, k: Int): Double {
        require(n > 0) { "The number of random numbers was <= 0" }
        require(k >= 1) { "The number of intervals was < 1" }
        val f = Array(k + 1) { DoubleArray(k + 1) }
        // tabulate the frequencies
        for (i in 1..n) {
            val u1: Double = rng.randU01()
            val j1 = ceil(k * u1).toInt()
            val u2: Double = rng.randU01()
            val j2 = ceil(k * u2).toInt()
            f[j1][j2] = f[j1][j2] + 1
        }
        var sum = 0.0
        val dk = k.toDouble()
        val e = n.toDouble() / (dk * dk)
        for (j1 in 1..k) {
            for (j2 in 1..k) {
                sum = sum + (f[j1][j2] - e) * (f[j1][j2] - e)
            }
        }
        sum = sum / e
        return sum
    }

    /** Performs the 3-D chi-squared serial test
     *
     * @param rng the thing that produces U(0,1) numbers, must not null
     * @param n number of random numbers to test
     * @param k the number of intervals in the test for each dimension
     * @return the chi-squared test statistic
     */
    fun chiSquaredSerial3DTestStatistic(rng: RandU01Ifc, n: Long, k: Int): Double {
        require(n > 0) { "The number of random numbers was <= 0" }
        require(k >= 1) { "The number of intervals was < 1" }
        val f = Array(k + 1) {
            Array(k + 1) {
                DoubleArray(
                    k + 1
                )
            }
        }
        // tabulate the frequencies
        for (i in 1..n) {
            val u1: Double = rng.randU01()
            val j1 = ceil(k * u1).toInt()
            val u2: Double = rng.randU01()
            val j2 = ceil(k * u2).toInt()
            val u3: Double = rng.randU01()
            val j3 = ceil(k * u3).toInt()
            f[j1][j2][j3] = f[j1][j2][j3] + 1
        }
        var sum = 0.0
        val dk = k.toDouble()
        val e = n.toDouble() / (dk * dk * dk)
        for (j1 in 1..k) {
            for (j2 in 1..k) {
                for (j3 in 1..k) {
                    sum = sum + (f[j1][j2][j3] - e) * (f[j1][j2][j3] - e)
                }
            }
        }
        sum = sum / e
        return sum
    }

    /** Performs the correlation test
     *
     * @param rng the thing that produces U(0,1) numbers, must not null
     * @param lag the lag to test
     * @param n the number to sample
     * @return the test statistic
     */
    fun correlationTest(rng: RandU01Ifc, lag: Int, n: Long): Double {
        require(lag > 0) { "The lag <= 0" }
        val h = floor((n - 1.0) / lag).toLong() - 1
        require(h > 0) { "(long)Math.floor((n-1)/lag) - 1 <= 0" }
        var sum = 0.0
        var u2 = 0.0
        var u1: Double = rng.randU01()
        for (k in 0..h) {
            for (j in 1..lag) {
                u2 = rng.randU01()
            }
            sum = sum + u1 * u2
            u1 = u2
        }
        val rho = 12.0 / (h + 1.0) * sum - 3.0
        val varrho = (13.0 * h + 7.0) / ((h + 1) * (h + 1))
        return rho / sqrt(varrho)
    }

    /** Performs the runs up test
     *
     * @param rng the thing that produces U(0,1) numbers, must not null
     * @param n the number to sample
     * @return the test statistic
     */
    fun runsUpTest(rng: RandU01Ifc, n: Long): Double {
        require(n > 0) { "The number of random numbers was <= 0" }
        val r = DoubleArray(7)
        var A: Double = rng.randU01()
        var J = 1
        for (i in 2..n) {
            val B: Double = rng.randU01()
            if (A >= B) {
                J = min(J.toDouble(), 6.0).toInt()
                r[J] = r[J] + 1
                J = 1
            } else {
                J = J + 1
            }
            //Replace A by B
            A = B
        }
        J = min(J.toDouble(), 6.0).toInt()
        r[J] = r[J] + 1

        //Compute R
        var R = 0.0
        for (i in 1..6) {
            for (j in 1..6) {
                R = R + a[i][j] * (r[i] - n * b[i]) * (r[j] - n * b[j])
            }
        }
        return R / n
    }
}