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
package ksl.utilities.random.mcmc

import ksl.utilities.math.FunctionIfc
import ksl.utilities.observers.Observable
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.statistic.Statistic

/**
 * An implementation for a 1-Dimensional Metropolis Hasting process. The
 * process is observable at each step
 * @param initialX the initial value to start generation process
 * @param targetFun the target function
 * @param proposalFun the proposal function
 */
class MetropolisHastings1D(var initialX: Double, targetFun: FunctionIfc, proposalFun: ProposalFunction1DIfc) :
    RandomIfc, Observable<Double>() {

    private val myTargetFun: FunctionIfc = targetFun
    private val myProposalFun: ProposalFunction1DIfc = proposalFun
    private val myAcceptanceStat: Statistic = Statistic("Acceptance Statistics")
    private val myObservedStat: Statistic = Statistic("Observed Value Statistics")

    override var rnStream: RNStreamIfc = KSLRandom.nextRNStream()

    override fun resetStartStream() {
        rnStream.resetStartStream()
    }

    override fun resetStartSubStream() {
        rnStream.resetStartSubStream()
    }

    override fun advanceToNextSubStream() {
        rnStream.advanceToNextSubStream()
    }

    override var antithetic: Boolean
        get() = rnStream.antithetic
        set(value) {
            rnStream.antithetic = value
        }

    /**
     *
     * @return the current state (x) of the process
     */
    var currentX = 0.0
        protected set

    /**
     *
     * @return the last proposed state (y)
     */
    var proposedY = 0.0
        protected set

    /**
     *
     * @return the previous state (x) of the process
     */
    var previousX = 0.0
        protected set

    /**
     *
     * @return the last value of the computed probability of acceptance
     */
    var lastAcceptanceProbability = 0.0
        protected set

    /**
     *
     * @return the last value of the target function evaluated at the proposed state (y)
     */
    var targetFunctionAtProposedY = 0.0
        protected set

    /**
     *
     * @return the last value of the target function evaluated at the current state (x)
     */
    var targetFunctionAtCurrentX = 0.0
        protected set

    /**
     *
     * @return true if the process has been initialized
     */
    var isInitialized: Boolean = false
        protected set

    /**
     *
     * @return true if the process has been warmed up
     */
    var isWarmedUp: Boolean = false
        protected set

    /** Runs a warmup period and assigns the initial value of the process to the last
     * value from the warmup process.
     *
     * @param warmUpAmount the amount of sampling for the burn-in (warmup) period
     */
    fun runWarmUpPeriod(warmUpAmount: Int) {
        val x = runAll(warmUpAmount)
        isWarmedUp = true
        isInitialized = false
        initialX = x
        resetStatistics()
    }

    /**  Resets statistics and sets the initial state the initial value or to the value
     * found via the warmup period (if the warmup period was run).
     *
     */
    fun initialize() {
        isInitialized = true
        currentX = initialX
        resetStatistics()
    }

    /**
     * Resets the automatically collected statistics
     */
    fun resetStatistics() {
        myObservedStat.reset()
        myAcceptanceStat.reset()
    }

    /**
     *
     * @param n  runs the process for n steps
     * @return the value of the process after n steps
     */
    fun runAll(n: Int): Double {
        require(n > 0) { "The number of iterations to run was less than or equal to zero." }
        initialize()
        var value = 0.0
        for (i in 1..n) {
            value = next()
        }
        return value
    }

    /**
     *
     * @return statistics for the proportion of the proposed state (y) that are accepted
     */
    val acceptanceStatistics: Statistic
        get() = myAcceptanceStat.instance()

    /**
     *
     * @return statistics on the observed (generated) values of the process
     */
    val observedStatistics: Statistic
        get() = myObservedStat.instance()

    /** Moves the process one step
     *
     * @return the next value of the process after proposing the next state (y)
     */
   fun next(): Double {
        if (!isInitialized) {
            initialize()
        }
        previousX = currentX
        proposedY = myProposalFun.generateProposedGivenCurrent(currentX)
        lastAcceptanceProbability = acceptanceFunction(currentX, proposedY)
        if (rnStream.randU01() <= lastAcceptanceProbability) {
            currentX = proposedY
            myAcceptanceStat.collect(1.0)
        } else {
            myAcceptanceStat.collect(0.0)
        }
        myObservedStat.collect(currentX)
        notifyObservers(currentX)
        return currentX
    }

    override fun value(): Double {
        return next()
    }

    override fun sample(): Double {
        return value()
    }

    /** Computes the acceptance function for each step
     *
     * @param currentX the current state
     * @param proposedY the proposed state
     * @return the evaluated acceptance function
     */
    private fun acceptanceFunction(currentX: Double, proposedY: Double): Double {
        val fRatio = functionRatio(currentX, proposedY)
        val pRatio: Double = myProposalFun.proposalRatio(currentX, proposedY)
        val ratio = fRatio * pRatio
        return minOf(ratio, 1.0)
    }

    /**
     *
     * @param currentX the current state
     * @param proposedY the proposed state
     * @return the ratio of f(y)/f(x) for the generation step
     */
    private fun functionRatio(currentX: Double, proposedY: Double): Double {
        val fx = myTargetFun.f(currentX)
        val fy = myTargetFun.f(proposedY)
        check(fx >= 0.0) { "The target function was < 0 at current = $currentX" }
        check(fy >= 0.0) { "The proposal function was < 0 at proposed = $proposedY" }
        val ratio: Double
        if (fx != 0.0) {
            ratio = fy / fx
            targetFunctionAtCurrentX = fx
            targetFunctionAtProposedY = fy
        } else {
            ratio = Double.POSITIVE_INFINITY
            targetFunctionAtCurrentX = fx
            targetFunctionAtProposedY = fy
        }
        return ratio
    }

    override fun toString(): String {
        return asString()
    }

    fun asString(): String {
        val sb = StringBuilder("MetropolisHastings1D")
        sb.appendLine()
        sb.append("Initialized Flag = ").append(this.isInitialized)
        sb.appendLine()
        sb.append("Burn In Flag = ").append(isWarmedUp)
        sb.appendLine()
        sb.append("Initial X =").append(initialX)
        sb.appendLine()
        sb.append("Current X = ").append(currentX)
        sb.appendLine()
        sb.append("Previous X = ").append(previousX)
        sb.appendLine()
        sb.append("Last Proposed Y= ").append(proposedY)
        sb.appendLine()
        sb.append("Last Prob. of Acceptance = ").append(lastAcceptanceProbability)
        sb.appendLine()
        sb.append("Last f(Y) = ").append(targetFunctionAtProposedY)
        sb.appendLine()
        sb.append("Last f(X) = ").append(targetFunctionAtCurrentX)
        sb.appendLine()
        sb.append("Acceptance Statistics")
        sb.appendLine()
        sb.append(myAcceptanceStat.asString())
        sb.appendLine()
        sb.append(myObservedStat.asString())
        return sb.toString()
    }

    companion object {
        /**
         *
         * @param initialX the initial value to start the burn in period
         * @param warmUpAmount the number of samples in the burn in period
         * @param targetFun the target function
         * @param proposalFun the proposal function
         * @return the created instance
         */
        fun create(
            initialX: Double, warmUpAmount: Int, targetFun: FunctionIfc,
            proposalFun: ProposalFunction1DIfc
        ): MetropolisHastings1D {
            val m = MetropolisHastings1D(initialX, targetFun, proposalFun)
            m.runWarmUpPeriod(warmUpAmount)
            return m
        }
    }
}