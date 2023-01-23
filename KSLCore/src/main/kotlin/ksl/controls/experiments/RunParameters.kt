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

/** Holds data about simulation runs. Replications start sequentially at the value provided by [replicationRange].
 *  For example, if startingReplication is 3, and there are 5 replications, then the parameters represents replications
 *  3, 4, 5, 6, 7.  Thus, the range would be 3..7.  This is to allow the specification of subsets of replications that
 *  constitute portions of a simulation run to be executed, perhaps concurrently.
 *
 * @param replicationRange the range of replications to simulate
 * @param lengthOfReplication the length of each replication
 * @param lengthOfWarmUp thh length of the warmup period for each replication
 * @param useAntithetic whether the antithetic option is on or off
 */
data class RunParameters(
    var replicationRange: IntRange = 1..1,
    var lengthOfReplication: Double = Double.POSITIVE_INFINITY,
    var lengthOfWarmUp: Double = 0.0,
    var useAntithetic: Boolean = false
) {
    init {
        require(replicationRange.first >= 1) { "Starting replication number must be >= 1" }
        require(replicationRange.last >= 1) { "Last replication number must be >= 1" }
        require(lengthOfReplication > 0.0) { "Length of replication must be > 0.0" }
        require(lengthOfWarmUp >= 0.0) { "Length of warm up period must be >= 0.0" }
    }

    val numberOfReplications: Int
        get() = replicationRange.last - replicationRange.first + 1

    fun instance(): RunParameters {
        return RunParameters(
            this.replicationRange,
            this.lengthOfReplication,
            this.lengthOfWarmUp,
            this.useAntithetic
        )
    }
}
