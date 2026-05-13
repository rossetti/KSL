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

package ksl.controls.experiments

import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 *  Model-intrinsic defaults for the run-parameter surface of an experiment.
 *
 *  Captures the twelve parameter values that describe *how a model is intended
 *  to be run*: replication count, replication length, warm-up length, stream
 *  options, antithetic option, and so on.  Deliberately omits the
 *  runtime-identification fields — `experimentName`, `experimentId`, `runName` —
 *  because those identify a specific run rather than the model itself.
 *
 *  Used as the run-parameter component of `ksl.simulation.ModelDescriptor`,
 *  the machine-readable snapshot that bundle tooling and consumers read.  Its
 *  shape is deliberately a subset of [ExperimentRunParameters]; the latter
 *  remains the input directive for actually running an experiment, where the
 *  runtime-identification fields are required.
 *
 *  Note: `currentReplicationNumber` is not stored here either — it is an
 *  in-flight runtime value, not a default.
 *
 *  @property numberOfReplications  the model's recommended replication count
 *  @property numChunks             the model's chunking convention; 1 means run
 *                                  all replications as a single chunk
 *  @property startingRepId         the first replication identifier (almost
 *                                  always 1)
 *  @property lengthOfReplication   replication length in model time units
 *  @property lengthOfReplicationWarmUp warm-up duration within a replication
 *  @property replicationInitializationOption whether the system state is
 *                                  re-initialized at the start of each replication
 *  @property maximumAllowedExecutionTimePerReplication soft wall-clock cap
 *  @property resetStartStreamOption whether random streams are reset prior to
 *                                  the first replication
 *  @property advanceNextSubStreamOption whether to advance sub-streams between
 *                                  replications (supports CRN)
 *  @property antitheticOption      whether antithetic replications are used
 *  @property numberOfStreamAdvancesPriorToRunning the number of stream advances
 *                                  applied before running the experiment
 *  @property garbageCollectAfterReplicationFlag whether `System.gc()` is invoked
 *                                  after each replication
 */
@Serializable
data class ExperimentRunDefaults(
    val numberOfReplications: Int,
    val numChunks: Int,
    val startingRepId: Int,
    val lengthOfReplication: Double,
    val lengthOfReplicationWarmUp: Double,
    val replicationInitializationOption: Boolean,
    @Serializable(with = DurationSerializer::class)
    val maximumAllowedExecutionTimePerReplication: Duration,
    val resetStartStreamOption: Boolean,
    val advanceNextSubStreamOption: Boolean,
    val antitheticOption: Boolean,
    val numberOfStreamAdvancesPriorToRunning: Int,
    val garbageCollectAfterReplicationFlag: Boolean
) {
    init {
        require(startingRepId >= 1) { "Starting replication number must be >= 1" }
        require(lengthOfReplication > 0.0) { "Length of replication must be > 0.0" }
        require(lengthOfReplicationWarmUp >= 0.0) { "Length of warm up period must be >= 0.0" }
        require(numberOfReplications >= 1) { "Number of replications must be >= 1" }
        require(numberOfStreamAdvancesPriorToRunning >= 0) {
            "Number of stream advances prior to running must be >= 0"
        }
        require(numChunks >= 1) { "Number of chunks must be >= 1" }
    }
}
