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

package ksl.observers

/**
 * The read-only query surface needed to report a response trace.
 *
 * Both the live capture observer [ResponseTrace] and the disk-backed reader
 * [ResponseTraceData] implement this, so the `responseTrace` reporting DSL
 * (in `ksl.utilities.io.report.extensions`) can render a trace either from a
 * running model or reloaded from a finished run's trace file.
 */
interface ResponseTraceDataIfc {

    /** The name of the traced response. */
    val name: String

    /**
     * `true` when the traced response is time-weighted (step-function
     * semantics) and `false` for observation-based responses.  Reporting
     * selects a state-variable sample-path plot vs. an observations plot
     * based on this.
     */
    val isTimeWeighted: Boolean

    /**
     * The distinct replication numbers present in the trace, in ascending
     * order.  Replications that were not recorded are not included.
     */
    val replicationNumbers: List<Int>

    /**
     * The times and values for replication [repNum] within the time window
     * [[startTime], [endTime]].  Element `"times"` holds the simulation times
     * at which the variable changed; element `"values"` holds the
     * corresponding values.
     *
     * @param repNum    replication number; must be > 0
     * @param startTime lower bound of the time window (inclusive); defaults to 0.0
     * @param endTime   upper bound of the time window (inclusive); defaults to [Double.MAX_VALUE]
     */
    fun traceDataMap(
        repNum: Int,
        startTime: Double = 0.0,
        endTime: Double = Double.MAX_VALUE
    ): Map<String, DoubleArray>
}
