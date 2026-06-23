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

import ksl.controls.experiments.ExperimentRunParameters

/**
 * Captures every model-intrinsic field of this [ExperimentRunParameters] as a
 * fully-populated [ExperimentRunOverrides] block (12 of the 15 fields; the
 * runtime-identity triple `experimentName` / `experimentId` / `runName` is
 * intentionally omitted because those fields belong to a run, not to a
 * document).
 *
 * Used by editors that hold a working [ExperimentRunParameters] in memory
 * (typically a model's defaults plus user edits) and need to embed those
 * values in a `ScenarioSpec.runOverrides` field for submission.  The
 * resulting overrides have every field non-null, which is functionally
 * equivalent to "override everything" — the user's edits travel verbatim
 * into the document.
 *
 * For sparse overrides where only a few fields differ from the model's
 * defaults, construct an [ExperimentRunOverrides] directly with just those
 * fields rather than calling this extension.
 */
fun ExperimentRunParameters.toOverrides(): ExperimentRunOverrides = ExperimentRunOverrides(
    numberOfReplications = numberOfReplications,
    numChunks = numChunks,
    startingRepId = startingRepId,
    lengthOfReplication = lengthOfReplication,
    lengthOfReplicationWarmUp = lengthOfReplicationWarmUp,
    replicationInitializationOption = replicationInitializationOption,
    maximumAllowedExecutionTimePerReplication = maximumAllowedExecutionTimePerReplication,
    resetStartStreamOption = resetStartStreamOption,
    advanceNextSubStreamOption = advanceNextSubStreamOption,
    antitheticOption = antitheticOption,
    numberOfStreamAdvancesPriorToRunning = numberOfStreamAdvancesPriorToRunning,
    garbageCollectAfterReplicationFlag = garbageCollectAfterReplicationFlag
)
