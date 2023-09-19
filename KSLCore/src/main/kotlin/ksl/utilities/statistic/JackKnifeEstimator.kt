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

import ksl.utilities.Interval
import ksl.utilities.KSLArrays
import ksl.utilities.distributions.StudentT
import kotlin.math.sqrt


class JackKnifeEstimator(originalData: DoubleArray, estimator: BSEstimatorIfc = BSEstimatorIfc.Average()) {
    init {
        require(originalData.size > 1) { "The supplied generate had only 1 data point" }
    }
    private val myEstimator: BSEstimatorIfc = estimator
    private val myOriginalPopStat: Statistic = Statistic("Original Pop Statistics", originalData)
    private val myOriginalData: DoubleArray = originalData.copyOf()
    private val myJackKnifeData =  DoubleArraySaver()
    /**
     *
     * @return the estimate from the supplied EstimatorIfc based on the original data
     */
    var originalDataEstimate: Double = myEstimator.getEstimate(originalData)
        private set

    /**
     *
     * @return the jackknife estimate of the standard error
     */
    var jackKnifeEstimateOfSE = 0.0
        private set

    /**
     *
     * @param level the level to set must be (0,1)
     */
    var defaultCILevel: Double = 0.95
        set(level) {
            require((level <= 0.0) || (level < 1.0)) { "Confidence Level must be (0,1)" }
            field = level
        }

    private val myJNStatistics: Statistic = Statistic("Jackknife Statistic")

    init {
        computeJackknife()
    }

    private fun computeJackknife() {
        val loos = DoubleArray(myOriginalData.size - 1)
        val jks = DoubleArray(myOriginalData.size)
        for (i in myOriginalData.indices) {
            // get the leave out generate missing i
            KSLArrays.copyWithout(i, myOriginalData, loos)
            // compute the estimator based on the leave out generate
            jks[i] = myEstimator.getEstimate(loos)
            // observe each estimate for jackknife stats
            myJNStatistics.collect(jks[i])
            myJackKnifeData.save(jks[i])
        }
        // now compute the std err of the jackknife
        val jne = jackKnifeEstimate
        val n: Double = myJNStatistics.count
        val s = Statistic()
        for (i in myOriginalData.indices) {
            val tmp = (jks[i] - jne) * (jks[i] - jne)
            s.collect(tmp)
        }
        jackKnifeEstimateOfSE = sqrt((n - 1.0) * s.average)
    }


    /**  nxoe - (n-1)xjne[i], where n is the number of observations, oe= original estimate
     * and jne[i] is the ith leave one out estimate
     *
     * @return an array containing the jackknife pseudo-values
     */
    val pseudoValues: DoubleArray
        get() {
            val a = DoubleArray(myOriginalData.size)
            val n = myOriginalData.size.toDouble()
            val ntheta = n * originalDataEstimate
            val thetai: DoubleArray = myJackKnifeData.savedData()
            for (i in a.indices) {
                a[i] = ntheta - (n - 1.0) * thetai[i]
            }
            return a
        }

    /**
     *
     * @return a copy of the original data
     */
    val originalData: DoubleArray
        get() = myOriginalData.copyOf()

    /**
     *
     * @return the number of observations in the original data set
     */
    val sampleSize: Double
        get() = myOriginalPopStat.count

    /**
     *
     * @return the average for the original data
     */
    val originalDataAverage: Double
        get() = myOriginalPopStat.average

    /**
     *
     * @return summary statistics for the original data
     */
    val originalDataStatistics: Statistic
        get() = myOriginalPopStat.instance()

    /**
     *
     * @return the average of the leave one out samples
     */
    val jackKnifeEstimate: Double
        get() = myJNStatistics.average

    /** The c.i. is based on the Student-t distribution and the
     * jackknife estimate and its estimate of the standard error
     *
     * @param level the confidence level, must be in (0,1)
     * @return the interval
     */
    fun jackKnifeConfidenceInterval(level: Double = defaultCILevel): Interval {
        require((level <= 0.0) || (level < 1.0)) { "Confidence Level must be (0,1)" }
        val dof = sampleSize - 1.0
        val alpha = 1.0 - level
        val p = 1.0 - alpha / 2.0
        val t: Double = StudentT.invCDF(dof, p)
        val jne = jackKnifeEstimate
        val se = jackKnifeEstimateOfSE
        val ll = jne - t * se
        val ul = jne + t * se
        return Interval(ll, ul)
    }

    /** The estimate is (n-1)x(jne - oe), where n = the number of observations, jne is the jackknife estimate
     * and oe = the original data estimate
     *
     * @return the estimate of the bias based on jackknifing
     */
    val jackKnifeBiasEstimate: Double
        get() {
            val n: Double = myOriginalPopStat.count
            val jne = jackKnifeEstimate
            val oe = originalDataEstimate
            return (n - 1.0) * (jne - oe)
        }

    /**
     *
     * @return the bias corrected jackknife estimate, getOriginalDataEstimate() - getJackKnifeBiasEstimate()
     */
    val biasCorrectedJackKnifeEstimate: Double
        get() = originalDataEstimate - jackKnifeBiasEstimate

    override fun toString(): String {
        return asString()
    }

    fun asString(): String {
        val sb = StringBuilder()
        sb.append("------------------------------------------------------")
        sb.append(System.lineSeparator())
        sb.append("Jackknife statistical results:")
        sb.append(System.lineSeparator())
        sb.append("------------------------------------------------------")
        sb.append(System.lineSeparator())
        sb.append("size of original = ").append(myOriginalPopStat.count)
        sb.append(System.lineSeparator())
        sb.append("original estimate = ").append(originalDataEstimate)
        sb.append(System.lineSeparator())
        sb.append("jackknife estimate = ").append(jackKnifeEstimate)
        sb.append(System.lineSeparator())
        sb.append("jackknife bias estimate = ").append(jackKnifeBiasEstimate)
        sb.append(System.lineSeparator())
        sb.append("bias corrected jackknife estimate = ").append(biasCorrectedJackKnifeEstimate)
        sb.append(System.lineSeparator())
        sb.append("std. err. of jackknife estimate = ").append(jackKnifeEstimateOfSE)
        sb.append(System.lineSeparator())
        sb.append("default c.i. level = ").append(defaultCILevel)
        sb.append(System.lineSeparator())
        sb.append("jackknife c.i. = ").append(jackKnifeConfidenceInterval())
        sb.append(System.lineSeparator())
        sb.append("------------------------------------------------------")
        return sb.toString()
    }
}