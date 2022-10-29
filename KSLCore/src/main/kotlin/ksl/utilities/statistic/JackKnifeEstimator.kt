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

import ksl.utilities.Interval
import ksl.utilities.KSLArrays
import ksl.utilities.distributions.StudentT
import kotlin.math.sqrt


class JackKnifeEstimator(originalData: DoubleArray, estimator: BSEstimatorIfc) {
    init {
        require(originalData.size > 1) { "The supplied generate had only 1 data point" }
    }
    private val myEstimator: BSEstimatorIfc = estimator
    private val myOriginalPopStat: Statistic = Statistic("Original Pop Statistics", originalData)
    private val myOrginalData: DoubleArray = originalData.copyOf()
    private val myJackNnifeData =  DoubleArraySaver()
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
        val loos = DoubleArray(myOrginalData.size - 1)
        val jks = DoubleArray(myOrginalData.size)
        for (i in myOrginalData.indices) {
            // get the leave out generate missing i
            KSLArrays.copyWithout(i, myOrginalData, loos)
            // compute the estimator based on the leave out generate
            jks[i] = myEstimator.getEstimate(loos)
            // observe each estimate for jackknife stats
            myJNStatistics.collect(jks[i])
            myJackNnifeData.save(jks[i])
        }
        // now compute the std err of the jackknife
        val jne = jackKnifeEstimate
        val n: Double = myJNStatistics.count
        val s = Statistic()
        for (i in myOrginalData.indices) {
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
            val a = DoubleArray(myOrginalData.size)
            val n = myOrginalData.size.toDouble()
            val ntheta = n * originalDataEstimate
            val thetai: DoubleArray = myJackNnifeData.savedData()
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
        get() = myOrginalData.copyOf()

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
    val biasCorrectedJackknifeEstimate: Double
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
        sb.append("bias corrected jackknife estimate = ").append(biasCorrectedJackknifeEstimate)
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