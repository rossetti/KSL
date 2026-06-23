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

package ksl.app.dist.config

import kotlinx.serialization.Serializable
import ksl.utilities.random.rvariable.parameters.RVData
import net.peanuuutz.tomlkt.TomlComment

/**
 * Serializable locator for one or more numeric datasets that feed a
 * distribution-fitting job.
 */
@Serializable
sealed class DataSourceReference {

    /**
     * Self-contained: one or more named series embedded directly in the
     * reference. Insertion order of `datasets` is preserved by the importer.
     */
    @Serializable
    data class Inline(
        @TomlComment("Map of series name to numeric values, embedded directly in the document (insertion order preserved).")
        val datasets: Map<String, DoubleArray>
    ) : DataSourceReference()

    /**
     * A delimited text file on disk. Layout, delimiter, header policy, and
     * column-role bindings together fully describe how to extract datasets.
     *
     * SINGLE layout requires WHITESPACE delimiter and ignores `hasHeader`,
     * `idColumn`, `valueColumn`, and `datasetColumns`.
     *
     * WIDE layout requires COMMA delimiter and `hasHeader = true`. If
     * `datasetColumns` is non-null, only those header names are imported,
     * in the order given; otherwise every column is imported in file order.
     *
     * LONG layout requires COMMA delimiter, `hasHeader = true`, and both
     * `idColumn` and `valueColumn` set. Rows are grouped by id (first-seen
     * order) into one dataset each.
     */
    @Serializable
    data class DelimitedFile(
        @TomlComment("String. Filesystem path to the delimited text file.")
        val path: String,
        @TomlComment("String. Field delimiter: \"COMMA\" or \"WHITESPACE\".")
        val delimiter: Delimiter = Delimiter.COMMA,
        @TomlComment("Boolean. Whether the first row is a header. Ignored for SINGLE layout.")
        val hasHeader: Boolean = true,
        @TomlComment("String. How datasets are arranged: \"SINGLE\", \"WIDE\" (column-per-dataset), or \"LONG\".")
        val layout: DatasetLayout = DatasetLayout.WIDE,
        @TomlComment("String. LONG layout only: header name of the column that tags each row's dataset.")
        val idColumn: String? = null,
        @TomlComment("String. LONG layout only: header name of the numeric value column.")
        val valueColumn: String? = null,
        @TomlComment("List. WIDE layout only: header names to import, in order. Omit to import every column.")
        val datasetColumns: List<String>? = null
    ) : DataSourceReference()

    /**
     * Synthetic data sampled from a KSL random variable, specified by the
     * canonical serializable `RVData` (typed `RVType` + a name→`DoubleArray`
     * parameter map, which faithfully carries scalar, integer, and
     * array-valued parameters).
     *
     * `streamNumber` follows KSL's stream convention against the default
     * provider: `0` (the default) draws the next stream, so each generation is
     * independent; a positive number selects that shared stream, which the
     * importer resets to its start before sampling, so a given positive
     * `streamNumber` reproduces.
     *
     * Produces exactly one dataset named `name`. Discrete RV types yield
     * integer-valued samples suitable for the discrete fitting path.
     */
    @Serializable
    data class Generated(
        @TomlComment("The random variable to sample from: its RV type plus a name to parameter-values map.")
        val rv: RVData,
        @TomlComment("Integer. Number of samples to draw; must be greater than 0.")
        val sampleSize: Int,
        @TomlComment(
            "Integer. Stream number against the default provider: 0 draws the next\n" +
            "stream (independent each time); a positive value selects that stream and\n" +
            "resets it before sampling, so the generation reproduces."
        )
        val streamNumber: Int = 0,
        @TomlComment("String. Name of the single dataset produced.")
        val name: String = "generated"
    ) : DataSourceReference()

    /**
     * A database table or query. Numeric columns are read into datasets.
     *
     * WIDE layout (the default): each numeric column becomes a dataset
     * (filtered to `datasetColumns`, in order, when given).
     *
     * LONG layout: rows are grouped by `idColumn` (first-seen order) into one
     * dataset each, with `valueColumn` supplying the numeric values; both must
     * be set.
     *
     * Embedded databases (SQLite, Derby) need no credentials; server databases
     * and credential resolution land in a later phase.
     */
    @Serializable
    data class Database(
        @TomlComment("Credential-free locator for the database connection (type, location, optional server).")
        val connection: DatabaseConnectionRef,
        @TomlComment("What to read: a table or a query.")
        val source: DbSource,
        @TomlComment("String. How datasets are arranged: \"WIDE\" (column-per-dataset) or \"LONG\".")
        val layout: DatasetLayout = DatasetLayout.WIDE,
        @TomlComment("String. LONG layout only: name of the column that tags each row's dataset.")
        val idColumn: String? = null,
        @TomlComment("String. LONG layout only: name of the numeric value column.")
        val valueColumn: String? = null,
        @TomlComment("List. WIDE layout only: numeric column names to import, in order. Omit to import all.")
        val datasetColumns: List<String>? = null
    ) : DataSourceReference()
}
