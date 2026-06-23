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

package ksl.app.dist.data

/**
 * Reshape helpers for moving between wide and long tabular layouts.
 *
 * Phase 1 ships only the long-to-datasets split, which is the operation
 * the importer needs to support LONG-layout files. Wide-to-long and
 * long-to-wide DataFrame helpers will be added when a downstream consumer
 * (e.g. batch expansion) requires them.
 */
object WideLongReshape {

    /**
     * Splits a long-format table into one numeric series per distinct id.
     *
     * `rows` are the data rows only (no header). `headers` names each
     * column in row-position order and is used to resolve `idColumn` and
     * `valueColumn` to indices.
     *
     * Ids preserve first-encountered order. Each group is materialized to
     * a DoubleArray in the order rows were supplied.
     *
     * Throws ImportException when: a required column name is not found in
     * `headers`, a row is shorter than the resolved column indices, a
     * value-column cell does not parse as a double, or a resulting group
     * is empty (cannot occur unless callers pre-filter).
     */
    fun splitLong(
        rows: List<Array<String>>,
        headers: List<String>,
        idColumn: String,
        valueColumn: String
    ): List<NamedDataset> {
        val idIndex = headers.indexOf(idColumn)
        if (idIndex < 0) {
            throw ImportException("id column '$idColumn' not found in headers $headers")
        }
        val valueIndex = headers.indexOf(valueColumn)
        if (valueIndex < 0) {
            throw ImportException("value column '$valueColumn' not found in headers $headers")
        }
        val grouped = LinkedHashMap<String, ArrayList<Double>>()
        for ((rowNum, row) in rows.withIndex()) {
            val maxIdx = maxOf(idIndex, valueIndex)
            if (row.size <= maxIdx) {
                throw ImportException(
                    "row ${rowNum + 1} has ${row.size} fields; need at least ${maxIdx + 1}"
                )
            }
            val id = row[idIndex]
            val raw = row[valueIndex]
            val value = raw.trim().toDoubleOrNull()
                ?: throw ImportException(
                    "row ${rowNum + 1} value-column '$valueColumn' is not numeric: '$raw'"
                )
            grouped.getOrPut(id) { ArrayList() }.add(value)
        }
        return grouped.map { (id, values) ->
            if (values.isEmpty()) {
                throw ImportException("dataset '$id' has no values")
            }
            NamedDataset(id, DoubleArray(values.size) { values[it] })
        }
    }

    /**
     * Splits parallel id / value columns into one numeric series per distinct
     * id, preserving first-encountered id order. Used by the database reader,
     * which has already-typed columns rather than string rows.
     *
     * `ids` and `values` must have the same length. Throws ImportException
     * when they do not, or when a resulting group is empty.
     */
    fun splitLong(ids: List<String>, values: DoubleArray): List<NamedDataset> {
        if (ids.size != values.size) {
            throw ImportException("id column size ${ids.size} != value column size ${values.size}")
        }
        val grouped = LinkedHashMap<String, ArrayList<Double>>()
        for (i in ids.indices) {
            grouped.getOrPut(ids[i]) { ArrayList() }.add(values[i])
        }
        return grouped.map { (id, vs) ->
            if (vs.isEmpty()) {
                throw ImportException("dataset '$id' has no values")
            }
            NamedDataset(id, DoubleArray(vs.size) { vs[it] })
        }
    }
}
