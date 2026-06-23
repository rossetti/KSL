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

package ksl.app.config.experiment

import kotlinx.serialization.Serializable
import net.peanuuutz.tomlkt.TomlComment

/**
 *  Document-level overrides for the model's baked-in simulation
 *  run parameters.  Each field is optional; `null` means "inherit
 *  whatever the model author set" (the substrate's default
 *  behaviour).  When non-null, the value replaces the model's
 *  setting on every design point's effective run parameters.
 *
 *  Per-design-point replications (`ReplicationSpec.Uniform` /
 *  `ReplicationSpec.PerPoint`) are deliberately NOT here — they
 *  live on the document's [ReplicationSpec] field because design-
 *  point replication count is a per-point concept (CCD centre
 *  points conventionally have higher reps than corners).  The
 *  Experiment app's Model tab cross-links the Uniform-replications
 *  case for discoverability without duplicating storage.
 *
 *  This block currently carries two knobs; reserved as the home
 *  for additional model-level run-parameter overrides if needed
 *  (e.g. `clearDataBeforeReplication`, `startTime`).
 */
@Serializable
data class RunParameterOverridesSpec(
    @TomlComment(
        "Optional double > 0.  When set, overrides the model's\n" +
        "baked-in lengthOfReplication for every design point.\n" +
        "Omit (null) to inherit the model default."
    )
    val lengthOfReplication: Double? = null,

    @TomlComment(
        "Optional double >= 0.  When set, overrides the model's\n" +
        "baked-in lengthOfReplicationWarmUp for every design point.\n" +
        "Must not exceed [lengthOfReplication] (when both are set).\n" +
        "Omit (null) to inherit the model default."
    )
    val lengthOfReplicationWarmUp: Double? = null
) {
    init {
        if (lengthOfReplication != null) {
            require(lengthOfReplication > 0.0) {
                "lengthOfReplication override must be > 0; got $lengthOfReplication"
            }
        }
        if (lengthOfReplicationWarmUp != null) {
            require(lengthOfReplicationWarmUp >= 0.0) {
                "lengthOfReplicationWarmUp override must be >= 0; got $lengthOfReplicationWarmUp"
            }
        }
        if (lengthOfReplication != null && lengthOfReplicationWarmUp != null) {
            require(lengthOfReplicationWarmUp <= lengthOfReplication) {
                "lengthOfReplicationWarmUp override ($lengthOfReplicationWarmUp) " +
                    "must not exceed lengthOfReplication override ($lengthOfReplication)"
            }
        }
    }
}
