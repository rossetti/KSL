package ksl.utilities.mcintegration

import ksl.utilities.statistic.Statistic

/**
 * Provides an interface for algorithms that perform Monte-Carlo experiments
 * See the abstract base class MCExperiment for further information.
 */
interface MCExperimentIfc : MCExperimentSetUpIfc {
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
    fun runInitialSample(): Double
}