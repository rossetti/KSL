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
/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package ksl.utilities.statistic

import ksl.utilities.distributions.Normal
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.random.rvariable.UniformRV

/** Tabulates the proportion and frequency for a random variable X &gt; a(i)
 * where a(i) are thresholds.
 *
 * @author rossetti
 * @param name the name of the estimator
 * @param thresholds the thresholds
 */
class ExceedanceEstimator(thresholds: DoubleArray = doubleArrayOf(1.0), name: String? = null) : Collector(name) {

    /**
     * The thresholds for the exceedance estimates
     *
     */
    private val myThresholds: DoubleArray = thresholds.copyOf().sortedArray()

    /**
     * Counts the number of times threshold is exceeded
     *
     */
    private val myCounts: DoubleArray = DoubleArray(thresholds.size)

    /**
     * Holds the number of observations observed
     */
    private var num = 0.0

    val numThresholds : Int = myThresholds.size

    constructor(vararg thresholds: Double) : this(thresholds, null)

    override fun collect(obs: Double) {
        num = num + 1.0
        for (i in myThresholds.indices) {
            if (obs > myThresholds[i]) {
                myCounts[i]++
            }
        }
        lastValue = obs
        notifyObservers(lastValue)
        emitter.emit(lastValue)
    }

    override fun reset() {
        super.reset()
        num = 0.0
        for (i in myCounts.indices) {
            myCounts[i] = 0.0
        }
    }

    /**
     *  the array of counts for each threshold
     */
    val frequencies: DoubleArray
        get() {
            return myCounts.copyOf()
        }

    /**
     * @param the ith threshold, must be in 0 but less than numThresholds
     * @return the frequency for the ith threshold
     */
    fun frequency(i: Int): Double {
        return myCounts[i]
    }

    /**
     * @param the ith threshold, must be in 0 but less than numThresholds
     * @return the proportion for the ith threshold
     */
    fun proportion(i: Int): Double {
        return if (num > 0) {
            myCounts[i] / num
        } else {
            0.0
        }
    }

    /**
     * the array of proportions for each threshold
     */
    val proportions: DoubleArray
        get() {
            val f = frequencies
            if (num == 0.0) {
                return f
            }
            for (i in f.indices) {
                f[i] = f[i] / num
            }
            return f
        }

    /**
     *  2-d array with 2 rows and numThreshold columns
     *  row[0] holds the thresholds
     *  row[1] holds the frequencies
     */
    val valueFrequencies: Array<DoubleArray>
        get() {
            val f = Array(2) { DoubleArray(myCounts.size) }
            for (i in myCounts.indices) {
                f[0][i] = myThresholds[i]
                f[1][i] = myCounts[i]
            }
            return f
        }

    /**
     *  2-d array with 2 rows and numThreshold columns
     *  row[0] holds the thresholds
     *  row[1] holds the proportions
     */
    val valueProportions: Array<DoubleArray>
        get() {
            val f = Array(2) { DoubleArray(myCounts.size) }
            for (i in myCounts.indices) {
                f[0][i] = myThresholds[i]
                if (num > 0) {
                    f[1][i] = myCounts[i] / num
                }
            }
            return f
        }

    /**
     * Gets the count of the number of the observations.
     *
     * @return A double representing the count
     */
    val count: Double
        get() = num

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("Exceedance Tabulation ")
        sb.append(name)
        sb.appendLine()
        sb.append("----------------------------------------\n")
        sb.append("Number of thresholds = ")
        sb.append(myThresholds.size)
        sb.appendLine()
        sb.append("Count = ")
        sb.append(count)
        sb.appendLine()
        sb.append("----------------------------------------")
        sb.appendLine()
        if (count > 0) {
            sb.append("Threshold \t Count \t p \t 1-p \n")
            for (i in myThresholds.indices) {
                val p = myCounts[i] / num
                val cp = 1.0 - p
                val str = "{X > ${myThresholds[i]}} \t ${myCounts[i]} \t $p \t $cp"
                sb.append(str)
                sb.appendLine()
            }
            sb.append("----------------------------------------")
            sb.appendLine()
        }
        return sb.toString()
    }

}

fun main() {
    val du = UniformRV(0.0, 100.0)
    val t = doubleArrayOf(0.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0)
    val f = ExceedanceEstimator(*t)
    f.collect(du.sample(10000))
    println("Testing")
    println(f)
    val n = NormalRV()
    val e = ExceedanceEstimator(Normal.stdNormalInvCDF(0.95))
    for (i in 1..10000) {
        e.collect(n.value)
    }
    println(e)
}