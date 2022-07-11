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
class ExceedanceEstimator(name: String?, thresholds: DoubleArray = doubleArrayOf(1.0)) : Collector(name) {

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

    val numThresholds = myThresholds.size

    constructor(vararg thresholds: Double) : this(null, thresholds)

    override fun collect(obs: Double) {
        super.collect(obs)
        num = num + 1.0
        for (i in myThresholds.indices) {
            if (obs > myThresholds[i]) {
                myCounts[i]++
            }
        }
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