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
import net.peanuuutz.tomlkt.TomlComment
import ksl.controls.experiments.ExperimentRunDefaults
import ksl.controls.experiments.ExperimentRunParameters
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
    @TomlComment(
        "Integer (>= 1) or omitted. Number of replications to run for\n" +
        "this scenario.  Omit to use the model's default."
    )
    val numberOfReplications: Int? = null,

    @TomlComment(
        "Integer (>= 1) or omitted. Number of chunks the replications\n" +
        "are split into for batching.  Advanced; omit to use the\n" +
        "model's default."
    )
    val numChunks: Int? = null,

    @TomlComment(
        "Integer or omitted. Starting replication identifier (zero-\n" +
        "based).  Use when resuming a partial sweep.  Omit to use the\n" +
        "model's default (typically 0)."
    )
    val startingRepId: Int? = null,

    @TomlComment(
        "Double or omitted. Length of one replication in the model's\n" +
        "time units (typically minutes; check the model's documentation).\n" +
        "Use Double.POSITIVE_INFINITY (printed as 'inf') for terminating\n" +
        "models that end via Model.endSimulation().  Omit to use the\n" +
        "model's default."
    )
    val lengthOfReplication: Double? = null,

    @TomlComment(
        "Double or omitted. Warm-up period at the start of each\n" +
        "replication, in the same time units as lengthOfReplication.\n" +
        "Observations before this point are discarded.  Omit to use\n" +
        "the model's default (typically 0.0)."
    )
    val lengthOfReplicationWarmUp: Double? = null,

    @TomlComment(
        "Boolean or omitted. When true, every replication re-initialises\n" +
        "the model from scratch; when false, state carries across\n" +
        "replications.  Omit to use the model's default (typically true)."
    )
    val replicationInitializationOption: Boolean? = null,

    @Serializable(with = DurationSerializer::class)
    @TomlComment(
        "ISO-8601 duration string (e.g. 'PT5M' for 5 minutes) or\n" +
        "omitted. Per-replication wall-clock cap.  When exceeded, the\n" +
        "replication is aborted and reported as failed.  Omit for no\n" +
        "limit."
    )
    val maximumAllowedExecutionTimePerReplication: Duration? = null,

    @TomlComment(
        "Boolean or omitted. RNG-stream control: when true, each\n" +
        "replication restarts its streams from the beginning of their\n" +
        "current substream.  Omit to use the model's default (typically\n" +
        "true)."
    )
    val resetStartStreamOption: Boolean? = null,

    @TomlComment(
        "Boolean or omitted. RNG-stream control: when true, advance to\n" +
        "the next substream after each replication.  Omit to use the\n" +
        "model's default (typically true)."
    )
    val advanceNextSubStreamOption: Boolean? = null,

    @TomlComment(
        "Boolean or omitted. When true, run with antithetic variates\n" +
        "(streams are flipped on alternate replications for variance\n" +
        "reduction).  Omit to use the model's default (typically false)."
    )
    val antitheticOption: Boolean? = null,

    @TomlComment(
        "Integer (>= 0) or omitted. Number of substream advances applied\n" +
        "before the first replication runs.  Advanced; use to align\n" +
        "with a previous run's RNG state.  Omit to use the model's\n" +
        "default (typically 0)."
    )
    val numberOfStreamAdvancesPriorToRunning: Int? = null,

    @TomlComment(
        "Boolean or omitted. When true, the JVM is asked to GC between\n" +
        "replications to flatten memory use.  Adds wall-clock overhead.\n" +
        "Omit to use the model's default (typically false)."
    )
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

    /**
     *  Runtime variant of [applyTo]: layers non-`null` overrides onto a
     *  full [ExperimentRunParameters] while preserving the three
     *  runtime-identity fields (`experimentName`, `experimentId`,
     *  `runName`) of [parameters].  Used by the orchestrator to compute
     *  the final parameter set handed to the engine, starting from the
     *  model's current parameters (which carry KSL-assigned identity)
     *  and overlaying the scenario's overrides on top.
     */
    fun applyTo(parameters: ExperimentRunParameters): ExperimentRunParameters =
        parameters.copy(
            numberOfReplications = numberOfReplications ?: parameters.numberOfReplications,
            numChunks = numChunks ?: parameters.numChunks,
            startingRepId = startingRepId ?: parameters.startingRepId,
            lengthOfReplication = lengthOfReplication ?: parameters.lengthOfReplication,
            lengthOfReplicationWarmUp =
                lengthOfReplicationWarmUp ?: parameters.lengthOfReplicationWarmUp,
            replicationInitializationOption =
                replicationInitializationOption ?: parameters.replicationInitializationOption,
            maximumAllowedExecutionTimePerReplication =
                maximumAllowedExecutionTimePerReplication
                    ?: parameters.maximumAllowedExecutionTimePerReplication,
            resetStartStreamOption =
                resetStartStreamOption ?: parameters.resetStartStreamOption,
            advanceNextSubStreamOption =
                advanceNextSubStreamOption ?: parameters.advanceNextSubStreamOption,
            antitheticOption = antitheticOption ?: parameters.antitheticOption,
            numberOfStreamAdvancesPriorToRunning =
                numberOfStreamAdvancesPriorToRunning
                    ?: parameters.numberOfStreamAdvancesPriorToRunning,
            garbageCollectAfterReplicationFlag =
                garbageCollectAfterReplicationFlag
                    ?: parameters.garbageCollectAfterReplicationFlag
        )

    companion object {
        /** An empty overrides object — every field is `null`. */
        val EMPTY: ExperimentRunOverrides = ExperimentRunOverrides()
    }
}
