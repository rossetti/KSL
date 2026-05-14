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

package ksl.app.config

import kotlinx.serialization.Serializable
import ksl.controls.experiments.DurationSerializer
import ksl.controls.experiments.ExperimentRunDefaults
import kotlin.time.Duration

/**
 *  Partial-override counterpart to [ksl.controls.experiments.ExperimentRunDefaults].
 *
 *  Each field is nullable; a `null` field means *"inherit the model's
 *  default for this parameter."*  A non-`null` field means *"override the
 *  model's default with this value."*  This is the run-parameter analogue
 *  of how controls and RV parameters override per key, on top of the
 *  model's intrinsic defaults.
 *
 *  Used by `ksl.app.config.ScenarioSpec` (post-reshape) to capture
 *  per-scenario run-parameter overrides without forcing the scenarios
 *  file to restate every field for every scenario.  The final
 *  [ksl.controls.experiments.ExperimentRunParameters] handed to the
 *  engine at submit time is computed by the orchestrator as
 *  `defaults + overrides + runtime-identity assignment`; see
 *  [applyTo].
 *
 *  Validation: when a field is non-`null`, it must satisfy the same
 *  bounds as the corresponding field on `ExperimentRunDefaults`.  The
 *  bounds checks live in the `init` block below; the
 *  `RunConfigurationValidator` re-runs them at the document level to
 *  produce `FieldError`s for the UI.
 *
 *  @property numberOfReplications  if non-null, the override; >= 1
 *  @property numChunks             if non-null, the override; >= 1
 *  @property startingRepId         if non-null, the override; >= 1
 *  @property lengthOfReplication   if non-null, the override; > 0.0
 *  @property lengthOfReplicationWarmUp  if non-null, the override; >= 0.0
 *  @property replicationInitializationOption if non-null, the override
 *  @property maximumAllowedExecutionTimePerReplication  if non-null,
 *                                  the override
 *  @property resetStartStreamOption  if non-null, the override
 *  @property advanceNextSubStreamOption  if non-null, the override
 *  @property antitheticOption        if non-null, the override
 *  @property numberOfStreamAdvancesPriorToRunning  if non-null,
 *                                  the override; >= 0
 *  @property garbageCollectAfterReplicationFlag  if non-null,
 *                                  the override
 */
@Serializable
data class ExperimentRunOverrides(
    val numberOfReplications: Int? = null,
    val numChunks: Int? = null,
    val startingRepId: Int? = null,
    val lengthOfReplication: Double? = null,
    val lengthOfReplicationWarmUp: Double? = null,
    val replicationInitializationOption: Boolean? = null,
    @Serializable(with = DurationSerializer::class)
    val maximumAllowedExecutionTimePerReplication: Duration? = null,
    val resetStartStreamOption: Boolean? = null,
    val advanceNextSubStreamOption: Boolean? = null,
    val antitheticOption: Boolean? = null,
    val numberOfStreamAdvancesPriorToRunning: Int? = null,
    val garbageCollectAfterReplicationFlag: Boolean? = null
) {
    init {
        require(numberOfReplications == null || numberOfReplications >= 1) {
            "Number of replications must be >= 1 (got $numberOfReplications)"
        }
        require(numChunks == null || numChunks >= 1) {
            "Number of chunks must be >= 1 (got $numChunks)"
        }
        require(startingRepId == null || startingRepId >= 1) {
            "Starting replication number must be >= 1 (got $startingRepId)"
        }
        require(lengthOfReplication == null || lengthOfReplication > 0.0) {
            "Length of replication must be > 0.0 (got $lengthOfReplication)"
        }
        require(lengthOfReplicationWarmUp == null || lengthOfReplicationWarmUp >= 0.0) {
            "Length of warm up period must be >= 0.0 (got $lengthOfReplicationWarmUp)"
        }
        require(numberOfStreamAdvancesPriorToRunning == null || numberOfStreamAdvancesPriorToRunning >= 0) {
            "Number of stream advances prior to running must be >= 0 " +
                    "(got $numberOfStreamAdvancesPriorToRunning)"
        }
    }

    /**
     *  Returns true when every field is `null`, i.e. the scenario inherits
     *  every default from the model.  An empty overrides object is
     *  equivalent to no overrides at all.
     */
    val isEmpty: Boolean
        get() = numberOfReplications == null &&
                numChunks == null &&
                startingRepId == null &&
                lengthOfReplication == null &&
                lengthOfReplicationWarmUp == null &&
                replicationInitializationOption == null &&
                maximumAllowedExecutionTimePerReplication == null &&
                resetStartStreamOption == null &&
                advanceNextSubStreamOption == null &&
                antitheticOption == null &&
                numberOfStreamAdvancesPriorToRunning == null &&
                garbageCollectAfterReplicationFlag == null

    /**
     *  Returns a fresh [ExperimentRunDefaults] equal to [defaults] with
     *  every non-`null` field of this overrides object overlaid on top.
     *  Fields left `null` here pass through from [defaults] unchanged.
     *
     *  Used by the orchestrator to compute the runtime parameter set
     *  for a scenario: take the model's defaults, apply the scenario's
     *  overrides, then layer on the runtime-identity triple
     *  (`experimentName`, `experimentId`, `runName`) to produce a full
     *  [ksl.controls.experiments.ExperimentRunParameters].
     */
    fun applyTo(defaults: ExperimentRunDefaults): ExperimentRunDefaults = defaults.copy(
        numberOfReplications = numberOfReplications ?: defaults.numberOfReplications,
        numChunks = numChunks ?: defaults.numChunks,
        startingRepId = startingRepId ?: defaults.startingRepId,
        lengthOfReplication = lengthOfReplication ?: defaults.lengthOfReplication,
        lengthOfReplicationWarmUp =
            lengthOfReplicationWarmUp ?: defaults.lengthOfReplicationWarmUp,
        replicationInitializationOption =
            replicationInitializationOption ?: defaults.replicationInitializationOption,
        maximumAllowedExecutionTimePerReplication =
            maximumAllowedExecutionTimePerReplication
                ?: defaults.maximumAllowedExecutionTimePerReplication,
        resetStartStreamOption = resetStartStreamOption ?: defaults.resetStartStreamOption,
        advanceNextSubStreamOption =
            advanceNextSubStreamOption ?: defaults.advanceNextSubStreamOption,
        antitheticOption = antitheticOption ?: defaults.antitheticOption,
        numberOfStreamAdvancesPriorToRunning =
            numberOfStreamAdvancesPriorToRunning
                ?: defaults.numberOfStreamAdvancesPriorToRunning,
        garbageCollectAfterReplicationFlag =
            garbageCollectAfterReplicationFlag
                ?: defaults.garbageCollectAfterReplicationFlag
    )

    companion object {
        /** An empty overrides object — every field is `null`. */
        val EMPTY: ExperimentRunOverrides = ExperimentRunOverrides()
    }
}
