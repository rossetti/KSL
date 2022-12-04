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

package ksl.utilities.mcintegration

import ksl.utilities.distributions.Normal
import ksl.utilities.statistic.Statistic
import java.lang.StringBuilder
import kotlin.math.ceil

/**
 * A functional interface for a single observation of the Monte-Carlo problem.
 *
 */
fun interface MCReplicationIfc {
    /**
     * @param j the current replication number. Could be used to implement more advanced
     * sampling that needs the current replication number. For example, antithetic sampling.
     */
    fun replication(j: Int): Double
}

/**
 * Provides for the running of a monte-carlo experiment.
 *
 * The simulation is performed in two loops: an outer loop called the macro replications and an inner loop called the micro replications.
 * The user specifies a desired (half-width) error bound, an initial sample size (k), and a maximum sample size limit (M) for
 * the macro replications.  The initial sample size is used to generate a pilot sample from which an estimate of the number of
 * samples needed to meet the absolute precision criteria. Let's call the estimated sample size, m.  If m > k, then an
 * additional (m-k) samples will be taken or until the error criteria is met or the maximum number of samples M is reached.
 * Thus, if m > M, and the error criterion is not met during the macro replications a total of M observations will be observed.
 * Thus, the total number of macro replications will not exceed M.  If the error criteria is met before M is reached, the
 * number of macro replications will be somewhere between k and M. The total number of macro replications can be
 * found by using the statistic() method to get the macro replication statistics and using the count() method.
 * Let's call the total number of macro replications executed, n.  The reason that the simulation occurs in two loops is
 * to make it more likely that the observed values for the macro replications are normally distributed because they will
 * be the sample average across the micro replications.  Thus, the theory for the stopping criteria and estimation of
 * the number of needed samples will be more likely to be valid.
 *
 * For each of the n, macro replications, a set of micro replications will be executed. Let r be the number of micro replications.
 * The micro replications represent the evaluation of r observations of the Monte-Carlo evaluation.
 *
 * Thus, the total number of observations will be n x r. The number of
 * micro replications is controlled by the user via the microRepSampleSize property. There is no error criteria checking for
 * the micro replications.
 *
 * By default, the number of macro replications should be relatively small and the number of micro
 * replications large.  Specific settings will be problem dependent.  The default initial sample size, k is 10, with a
 * maximum number of macro replications of M = 100.  The default half-width error bound is 0.0001.  The default setting
 * of the number of micro replications, r, is 1000.  Again, these are all adjustable by the user.
 *
 * The user can check if the error criteria was met after the evaluation. If it is not met, the user can
 * adjust the initial sample size, desired error, maximum sample size, or number of micro replications and run another evaluation.
 *
 * The statistics associated with the estimate are readily available. The user may
 * reset the underlying random number stream if a reproducible result is desired within the same execution frame.
 *
 * By default, the underlying random number stream is not reset with each invocation of the runSimulation() method.
 * The default confidence level is 95 percent.
 *
 * Be aware that small desired absolute error may result in large execution times.
 *
 * Implementors of subclasses of this abstract base class are responsible for implementing the abstract method,
 * replication(int j). This method is responsible for computing a single evaluation of the simulation model.
 */
open class MCExperiment(sampler: MCReplicationIfc? = null) : MCExperimentIfc {

    override var printInitialOption: Boolean = false

    protected val macroReplicationStatistics = Statistic()

    protected val replicationStatistics = Statistic()

    protected val mcReplication = sampler

    override var initialSampleSize = 30
        set(value) {
            require(value >= 2.0) { "The initial sample size must be >= 2" }
            field = value
        }

    override var desiredHWErrorBound = 0.001
        set(value) {
            require(value > 0.0) { "The desired relative precision must be > 0.0" }
            field = value
        }

    override var resetStreamOption = false

    override var microRepSampleSize = 100
        set(value) {
            require(value > 0.0) { "The micro replication sample size must be >= 1" }
            field = value
        }

    override var maxSampleSize: Long = 10000
        set(value) {
            require(value >= initialSampleSize) { "The maximum sample size must be >= $initialSampleSize" }
            field = value
        }

    override var confidenceLevel: Double = 0.95
        set(value) {
            macroReplicationStatistics.confidenceLevel = value
            field = value
        }

    override fun checkStoppingCriteria(): Boolean {
        return if (macroReplicationStatistics.count < 2.0) {
            false
        } else macroReplicationStatistics.halfWidth <= desiredHWErrorBound
    }

    override fun estimateSampleSize(): Double {
        if (macroReplicationStatistics.count < 2.0) {
            return Double.NaN
        }
        val sampleSize = macroReplicationStatistics.estimateSampleSize(desiredHWErrorBound)
        return sampleSize.toDouble()
    }

    override fun estimateSampleSizeForRelativeError(relativeError: Double): Double {
        if (macroReplicationStatistics.count < 2.0) {
            return Double.NaN
        }
        require(relativeError > 0.0) { "The relative error bound must be > 0.0" }
        val adjRE = relativeError / (1.0 + relativeError)
        val `var` = macroReplicationStatistics.variance
        val alpha = 1.0 - macroReplicationStatistics.confidenceLevel
        val ao2 = alpha / 2.0
        val z = Normal.stdNormalInvCDF(1.0 - ao2)
        val dn = adjRE * macroReplicationStatistics.average
        return ceil(`var` * (z * z) / (dn * dn))
    }

    override fun runSimulation(): Double {
        macroReplicationStatistics.reset()
        val numNeeded = runInitialSample()
        val k = maxSampleSize - initialSampleSize
        val m = minOf(numNeeded, k.toDouble()).toInt()
        // error criterion may have been met by initial sample,
        // in which case m = 0 and no further micro-replications will be run
        beforeMacroReplications()
        var converged = false
        for (i in 1..m) {
            macroReplicationStatistics.collect(runMicroReplications())
            if (checkStoppingCriteria()) {
                converged = true
                break
            }
        }
        if (!converged) {
            // ran the estimated m, but did not meet the criteria, continue running up to max
            for (i in 1..k - m) {
                macroReplicationStatistics.collect(runMicroReplications())
                if (checkStoppingCriteria()) {
                    break
                }
            }
        }
        afterMacroReplications()
        return macroReplicationStatistics.average
    }

    /**
     * Allows insertion of code before the macro replication loop
     */
    protected fun beforeMacroReplications() {}

    /**
     * Allows insertion of code before the macro replications run
     */
    protected fun afterMacroReplications() {}

    override fun statistics(): Statistic = macroReplicationStatistics.instance()

    override fun runInitialSample(printResultsOption: Boolean): Double {
        macroReplicationStatistics.reset()
        for (i in 1..initialSampleSize) {
            macroReplicationStatistics.collect(runMicroReplications())
            if (checkStoppingCriteria()) {
                return 0.0 // met criteria, no more needed
            }
        }
        // ran through entire initial sample, estimate requirement
        val m = estimateSampleSize()
        // m is the estimated total needed assuming no sampling has been done
        // it could be possible that m is estimated less than the initial sample size
        // handle that case with max
        if (printResultsOption){
            println("The initial sample of size $initialSampleSize of micro-replications of size $microRepSampleSize was executed.")
            print("The current estimate is ${macroReplicationStatistics.average}")
            println("with half-width = ${macroReplicationStatistics.halfWidth}")
            print("Estimated sample size needed to meet the desired half-width criteria of $desiredHWErrorBound = ")
            println(m)
            val k = maxOf(0.0, m - initialSampleSize)
            println("The simulation will run $k additional micro-replications of size $microRepSampleSize.")
        }
        return maxOf(0.0, m - initialSampleSize)
    }

    /**
     * @return returns the sample average across the replications
     */
    protected fun runMicroReplications(): Double {
        replicationStatistics.reset()
        beforeMicroReplications()
        for (r in 1..microRepSampleSize) {
            replicationStatistics.collect(replication(r))
        }
        afterMicroReplications()
        return replicationStatistics.average
    }

    /**
     * Allows insertion of code before the micro replications run
     */
    protected fun beforeMicroReplications() {}

    /**
     * Allows insertion of code after the micro replications run
     */
    protected fun afterMicroReplications() {}

    /**
     * Runs the rth replication for a sequence of replications
     * r = 1, 2, ... , getMicroRepSampleSize()
     *
     * @param j the number of the replication in the sequence of replications
     * @return the simulated result from the replication
     */
    override fun replication(j: Int): Double {
        if (mcReplication != null) {
            return mcReplication.replication(j)
        } else {
            TODO("You need to implement the replication method or supply a MCReplicationIfc")
        }
    }

    override fun toString(): String {
        val sb = StringBuilder("Monte Carlo Integration Results")
        sb.appendLine()
        sb.append("initial Sample Size = ").append(initialSampleSize)
        sb.appendLine()
        sb.append("max Sample Size = ").append(maxSampleSize)
        sb.appendLine()
        sb.append("reset Stream OptionOn = ").append(resetStreamOption)
        sb.appendLine()
        sb.append("Estimated sample size needed to meet criteria = ")
        sb.append(estimateSampleSize())
        sb.appendLine()
        sb.append("desired half-width error bound = ").append(desiredHWErrorBound)
        sb.appendLine()
        val hw = macroReplicationStatistics.halfWidth
        sb.append("actual half-width = ").append(hw)
        sb.appendLine()
        sb.append("error gap (hw - bound) = ").append(hw - desiredHWErrorBound)
        sb.appendLine()
        sb.append("-----------------------------------------------------------")
        sb.appendLine()
        val converged = checkStoppingCriteria()
        if (!converged) {
            sb.append("The half-width criteria was not met!")
            sb.appendLine()
            sb.append("The user should consider one of the following:")
            sb.appendLine()
            sb.append("1. increase the desired error bound using desiredHWErrorBound")
            sb.appendLine()
            sb.append("2. increase the number of macro replications using maxSampleSize")
            sb.appendLine()
            sb.append("2. increase the number of micro replications using microRepSampleSize")
        } else {
            sb.append("The half-width criteria was met!")
        }
        sb.appendLine()
        sb.append("-----------------------------------------------------------")
        sb.appendLine()
        sb.append("**** Sampling results ****")
        sb.appendLine()
        sb.append("Number of macro replications executed = ")
        sb.append(macroReplicationStatistics.count)
        sb.appendLine()
        sb.append("Number of micro replications per macro replication = ")
        sb.append(microRepSampleSize)
        sb.appendLine()
        sb.append("Total number of observations = ")
        sb.append(macroReplicationStatistics.count * microRepSampleSize)
        sb.appendLine()
        if (macroReplicationStatistics.count == 0.0) {
            sb.append("**** There were no macro replications executed for results.")
        } else {
            sb.append(macroReplicationStatistics)
        }
        return sb.toString()
    }
}