package ksl.controls.experiments

import ksl.utilities.NameIfc
import ksl.utilities.statistic.RegressionResultsIfc

/**
 * Lightweight reporting and analysis surface shared by designed experiments.
 *
 * This interface intentionally describes executed design results, not execution
 * mechanics. Sequential and parallel designed experiments differ in how they
 * create and run models, but reporting only needs the design, executed runs,
 * response names, observations, and regression bridge.
 */
interface DesignedExperimentIfc : NameIfc {

    /**
     * The experimental design associated with the executed design points.
     */
    val design: ExperimentalDesignIfc

    /**
     * Executed simulation runs, one run for each simulated design point.
     */
    val simulationRuns: List<SimulationRun>

    /**
     * The number of design points that have been executed.
     */
    val numSimulationRuns: Int

    /**
     * The names of responses or counters available for reporting.
     */
    val responseNames: List<String>

    /**
     * Returns a map of design-point label to per-replication observations for [responseName].
     */
    fun observationsAsMap(responseName: String): Map<String, DoubleArray>

    /**
     * Performs the regression of [linearModel] for [responseName].
     */
    fun regressionResults(
        responseName: String,
        linearModel: LinearModel,
        coded: Boolean
    ): RegressionResultsIfc
}
