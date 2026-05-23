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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.peanuuutz.tomlkt.TomlComment

/**
 *  How many replications each design point should run.  Two variants:
 *  uniform (every point runs the same count) or per-point (a default
 *  plus an index-keyed override map).
 *
 *  Per-point overrides are typical for central-composite designs that
 *  want more replicates at the centre than at the factorial-cube
 *  corners — a common variance-estimation pattern.
 */
@Serializable
sealed class ReplicationSpec {
    /**
     *  Every design point runs the same number of replications.
     *  The simplest and most common case.
     */
    @Serializable
    @SerialName("uniform")
    data class Uniform(
        @TomlComment(
            "Integer >= 1.  Number of replications to run at every\n" +
            "design point.  Larger values reduce variance estimates;\n" +
            "the cost grows linearly with the design-point count."
        )
        val replications: Int
    ) : ReplicationSpec() {
        init {
            require(replications >= 1) { "replications must be >= 1; got $replications" }
        }
    }

    /**
     *  A document-level default replication count plus per-point
     *  overrides keyed by design-point index (0-based, in the order
     *  the design enumerates them).  Indices not present in
     *  [overrides] use [default].
     *
     *  The index space is dynamic: it depends on which `DesignSpec`
     *  variant is in use and how many points it generates.  Submit-
     *  time validation rejects override indices outside the enumerated
     *  range.
     */
    @Serializable
    @SerialName("perPoint")
    data class PerPoint(
        @TomlComment(
            "Integer >= 1.  Default replication count for design points\n" +
            "not listed in [overrides]."
        )
        val default: Int,

        @TomlComment(
            "Map of design-point index (0-based) to replication count.\n" +
            "Keys must be valid indices into the enumerated design;\n" +
            "out-of-range indices are rejected at submit time.  Values\n" +
            "must be >= 1."
        )
        val overrides: Map<Int, Int>
    ) : ReplicationSpec() {
        init {
            require(default >= 1) { "default must be >= 1; got $default" }
            for ((idx, count) in overrides) {
                require(idx >= 0) { "override index must be >= 0; got $idx" }
                require(count >= 1) {
                    "override replication count must be >= 1; got $count at index $idx"
                }
            }
        }
    }
}
