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

import ksl.utilities.io.tabularfiles.TabularInputFile
import java.nio.file.Path

/**
 * Disk-backed [ResponseTraceDataIfc] that reloads a response trace written by
 * a [ResponseTrace] during a finished run.  Unlike the live observer, this
 * needs no model — it reopens the on-disk trace file and answers the same
 * queries, so the `responseTrace` reporting DSL can render a trace after the
 * run from the file alone.
 *
 * The trace file does not record whether the response is time-weighted, so
 * [isTimeWeighted] is supplied by the caller (e.g. from a model probe).
 *
 * The `repNum`, `time`, and `value` columns are read once on construction via
 * the public [TabularInputFile] API and held in memory; filtering by
 * replication and time window is done in‑memory.  Pair this with the
 * per-response capture caps on [ResponseTrace] to keep traces small.
 *
 * @param pathToFile path to the `<responseName>_Trace` file on disk.
 * @param isTimeWeighted whether the traced response is time-weighted.
 * @param name the response name; defaults to the file name with the `_Trace`
 *   suffix removed.
 */
class ResponseTraceData(
    pathToFile: Path,
    override val isTimeWeighted: Boolean,
    override val name: String = pathToFile.fileName.toString().removeSuffix("_Trace")
) : ResponseTraceDataIfc {

    private val repNums: DoubleArray
    private val times: DoubleArray
    private val values: DoubleArray

    init {
        val tif = TabularInputFile(pathToFile)
        try {
            repNums = tif.fetchNumericColumn("repNum")
            times = tif.fetchNumericColumn("time")
            values = tif.fetchNumericColumn("value")
        } finally {
            tif.close()
        }
    }

    override val replicationNumbers: List<Int>
        get() = repNums.map { it.toInt() }.distinct().sorted()

    override fun traceDataMap(repNum: Int, startTime: Double, endTime: Double): Map<String, DoubleArray> {
        val ts = ArrayList<Double>()
        val vs = ArrayList<Double>()
        for (i in repNums.indices) {
            if (repNums[i].toInt() == repNum && times[i] in startTime..endTime) {
                ts.add(times[i])
                vs.add(values[i])
            }
        }
        return mapOf("times" to ts.toDoubleArray(), "values" to vs.toDoubleArray())
    }
}
