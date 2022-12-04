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

import ksl.utilities.statistic.Statistic

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
 * Provides an interface for algorithms that perform Monte-Carlo experiments
 * See the abstract base class MCExperiment for further information.
 */
interface MCExperimentIfc : MCExperimentSetUpIfc, MCReplicationIfc {

    /**
     * Indicates if initial results are printed
     */
    var printInitialOption :Boolean

    /**
     * @return true if the relative error meets the desired with the appropriate level of confidence
     */
    fun checkStoppingCriteria(): Boolean

    /**
     * @return the number of samples needed to meet the half-width error criteria with the specified level of confidence
     */
    fun estimateSampleSize(): Double

    /**
     * See page 513 of Law & Kelton
     *
     * @param relativeError a relative error bound
     * @return the recommended sample size
     */
    fun estimateSampleSizeForRelativeError(relativeError: Double): Double

    /**
     * @return the estimated result of the simulation
     */
    fun runSimulation(): Double

    /**
     * @return the sampling statistics from the simulation
     */
    fun statistics(): Statistic

    /**
     * The purpose of the initial sample is to estimate the variability
     * and determine an approximate number of additional samples needed
     * to meet the desired absolute error. This method must ensure
     * or assumes that no previous sampling has occurred. All
     * statistical accumulators should be reset when this is executed.
     *
     * @return the number of additional samples needed
     */
    fun runInitialSample(printResultsOption: Boolean = printInitialOption): Double
}