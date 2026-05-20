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

package ksl.utilities.io.dbutil

/**
 * Immutable point-in-time snapshot of model state at a simulation lifecycle boundary.
 *
 * Snapshots carry the existing [KSLDatabase] DTO classes directly, preserving
 * `element_id_fk` relational integrity across all database tables.  They are
 * emitted by `SimulationLifecycleBridge` and consumed by `InMemorySnapshotCollector`
 * and `SnapshotBatchWriter`.
 *
 * Subscribers are expected to attach before simulation starts.  Dynamic mid-run
 * subscription is not supported.
 */
sealed class SimulationSnapshot {

    /**
     * Emitted once before the first replication of an experiment.
     *
     * Carries the experiment record, the initial simulation run record, the full
     * model element hierarchy, all control values, and all random variable
     * parameter settings captured at experiment start.
     */
    data class ExperimentStarted(
        val experiment: ExperimentTableData,
        val simulationRun: SimulationRunTableData,
        val modelElements: List<ModelElementTableData>,
        val controls: List<ControlTableData>,
        val rvParameters: List<RvParameterTableData>
    ) : SimulationSnapshot()

    /**
     * Emitted once after each replication completes successfully.
     *
     * Carries within-replication statistics for responses and counters, and
     * batch statistics if batching was active during the replication.
     */
    data class ReplicationCompleted(
        val repId: Int,
        val withinRepStats: List<WithinRepStatTableData>,
        val withinRepCounterStats: List<WithinRepCounterStatTableData>,
        val batchStats: List<BatchStatTableData>
    ) : SimulationSnapshot()

    /**
     * Emitted once after all replications of an experiment complete successfully.
     *
     * Carries the experiment identity record, the finalized simulation run record
     * (with end timestamp and final replication count), and all across-replication
     * aggregates, histogram data, frequency data, and time-series response data.
     *
     * The [experiment] field mirrors the same record in [ExperimentStarted] so
     * that consumers receiving only the completion snapshot can still identify
     * which experiment produced the results without having to retain the
     * earlier start snapshot.  In particular, `experiment.exp_name` is the
     * authoritative experiment / scenario identifier; the
     * [SimulationRunTableData.run_name] field on [simulationRun] is a separate,
     * usually-empty per-run label and should not be used as an experiment id.
     */
    data class ExperimentCompleted(
        val simulationRun: SimulationRunTableData,
        val acrossRepStats: List<AcrossRepStatTableData>,
        val histograms: List<HistogramTableData>,
        val frequencies: List<FrequencyTableData>,
        val timeSeries: List<TimeSeriesResponseTableData>,
        /** Identity record for the experiment that produced this snapshot.
         *  Mirrors [ExperimentStarted.experiment].  Defaults to an empty
         *  [ExperimentTableData] so callers constructing `ExperimentCompleted`
         *  outside the lifecycle bridge (e.g. in test fixtures) stay
         *  source-compatible. */
        val experiment: ExperimentTableData = ExperimentTableData()
    ) : SimulationSnapshot()

    /**
     * Emitted if the experiment terminates due to an unhandled exception.
     *
     * Carries an error message, the partial run record (populated as far as the
     * simulation progressed), and the replication count at the point of failure.
     */
    data class ExperimentFailed(
        val errorMessage: String,
        val partialSimulationRun: SimulationRunTableData,
        val completedRepCount: Int
    ) : SimulationSnapshot()
}
