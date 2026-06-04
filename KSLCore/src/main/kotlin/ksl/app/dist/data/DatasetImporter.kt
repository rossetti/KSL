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

import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.DatasetLayout
import ksl.app.dist.config.Delimiter
import ksl.utilities.io.CSVUtil
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.random.rvariable.RVType
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves a serializable `DataSourceReference` into a list of in-memory
 * numeric datasets. Implementations are expected to be pure functions of
 * their reference plus the file system / inline data; no side effects on
 * shared state.
 *
 * The default implementation is `DefaultDatasetImporter`; the interface
 * exists so front-ends and tests can substitute alternative readers
 * (in-memory fixtures, classpath resources, mocked databases later).
 */
fun interface DatasetImporter {
    /**
     * Resolves the reference and returns one or more non-empty datasets.
     * Throws ImportException when the reference is unresolvable or any
     * dataset would be empty.
     */
    fun import(reference: DataSourceReference): List<NamedDataset>

    companion object {
        val default: DatasetImporter = DefaultDatasetImporter()
    }
}

/**
 * Default importer: dispatches on the reference variant and the
 * (layout, delimiter) pair, delegating to existing KSL IO helpers.
 *
 * Mapping:
 *  - Inline                         -> wrap entries as NamedDataset, preserving Map order
 *  - DelimitedFile + SINGLE + WS    -> KSLFileUtil.scanToArray; dataset name = file stem
 *  - DelimitedFile + WIDE + COMMA   -> CSVUtil.readToColumns; one dataset per column,
 *                                      optionally filtered to `datasetColumns` (in filter order)
 *  - DelimitedFile + LONG + COMMA   -> CSVUtil.readRowsToListOfStringArrays + WideLongReshape.splitLong
 *  - Generated                      -> sample a KSL random variable (RVType + parameters)
 *  - Database                       -> DatabaseDataReader (table/query -> DataFrame -> datasets)
 *
 * `credentialResolver` resolves database credentials at run time for server
 * connections; it defaults to [DefaultCredentialResolver] (env vars / TOML
 * secrets file). A front-end that needs to prompt for credentials constructs
 * this importer with its own resolver and passes it to the session.
 */
class DefaultDatasetImporter(
    private val credentialResolver: CredentialResolver = DefaultCredentialResolver
) : DatasetImporter {

    override fun import(reference: DataSourceReference): List<NamedDataset> = when (reference) {
        is DataSourceReference.Inline -> importInline(reference)
        is DataSourceReference.DelimitedFile -> importDelimitedFile(reference)
        is DataSourceReference.Generated -> importGenerated(reference)
        is DataSourceReference.Database -> DatabaseDataReader.read(reference, credentialResolver)
    }

    private fun importGenerated(reference: DataSourceReference.Generated): List<NamedDataset> {
        if (reference.sampleSize <= 0) {
            throw ImportException("generated sample size must be > 0; was ${reference.sampleSize}")
        }
        val rvType = try {
            RVType.valueOf(reference.rvType)
        } catch (e: IllegalArgumentException) {
            throw ImportException("unknown rv type '${reference.rvType}'", e)
        }
        val params = rvType.rvParameters
        reference.parameters.forEach { (name, value) ->
            val changed = params.changeParameter(name, value)
            if (!changed) {
                throw ImportException("unknown parameter '$name' for rv type '${reference.rvType}'")
            }
        }
        // Use the application's default stream provider (KSL's standard stream
        // management). streamNumber 0 draws a fresh "next" stream each call
        // (independent generations); a positive streamNumber selects that
        // shared stream and resetStartStream() puts it at a known start, so a
        // given positive streamNumber reproduces.
        val data = try {
            val rv = params.createRVariable(reference.streamNumber)
            rv.resetStartStream()
            rv.sample(reference.sampleSize)
        } catch (e: Exception) {
            throw ImportException("failed to generate data for rv type '${reference.rvType}': ${e.message}", e)
        }
        if (data.isEmpty()) {
            throw ImportException("generated no data for rv type '${reference.rvType}'")
        }
        return listOf(NamedDataset(reference.name, data))
    }

    private fun importInline(reference: DataSourceReference.Inline): List<NamedDataset> {
        if (reference.datasets.isEmpty()) {
            throw ImportException("inline reference contains no datasets")
        }
        return reference.datasets.map { (name, data) ->
            if (data.isEmpty()) {
                throw ImportException("inline dataset '$name' is empty")
            }
            NamedDataset(name, data)
        }
    }

    private fun importDelimitedFile(reference: DataSourceReference.DelimitedFile): List<NamedDataset> {
        val path = Paths.get(reference.path)
        if (!Files.exists(path)) {
            throw ImportException("file not found: ${reference.path}")
        }
        validateLayoutDelimiter(reference)
        return when (reference.layout) {
            DatasetLayout.SINGLE -> importSingle(path)
            DatasetLayout.WIDE -> importWide(path, reference.datasetColumns)
            DatasetLayout.LONG -> importLong(
                path,
                requireColumn(reference.idColumn, "idColumn", DatasetLayout.LONG),
                requireColumn(reference.valueColumn, "valueColumn", DatasetLayout.LONG)
            )
        }
    }

    private fun validateLayoutDelimiter(ref: DataSourceReference.DelimitedFile) {
        when (ref.layout) {
            DatasetLayout.SINGLE -> if (ref.delimiter != Delimiter.WHITESPACE) {
                throw ImportException(
                    "SINGLE layout requires WHITESPACE delimiter; got ${ref.delimiter}"
                )
            }
            DatasetLayout.WIDE, DatasetLayout.LONG -> {
                if (ref.delimiter != Delimiter.COMMA) {
                    throw ImportException(
                        "${ref.layout} layout requires COMMA delimiter; got ${ref.delimiter}"
                    )
                }
                if (!ref.hasHeader) {
                    throw ImportException("${ref.layout} layout requires hasHeader = true")
                }
            }
        }
    }

    private fun requireColumn(value: String?, role: String, layout: DatasetLayout): String =
        value ?: throw ImportException("$layout layout requires $role to be set")

    private fun importSingle(path: Path): List<NamedDataset> {
        val data = KSLFileUtil.scanToArray(path)
        if (data.isEmpty()) {
            throw ImportException("file ${path} produced no numeric values")
        }
        return listOf(NamedDataset(fileStem(path), data))
    }

    private fun importWide(path: Path, filter: List<String>?): List<NamedDataset> {
        val headers = mutableListOf<String>()
        val columns = CSVUtil.readToColumns(headers, path)
        if (headers.isEmpty()) {
            throw ImportException("file ${path} produced no header row")
        }
        if (columns.isEmpty()) {
            throw ImportException("file ${path} produced no data rows")
        }
        val selected = filter?.also { requested ->
            val missing = requested.filterNot { it in headers }
            if (missing.isNotEmpty()) {
                throw ImportException("requested columns not found in $headers: $missing")
            }
        } ?: headers
        return selected.map { name ->
            val index = headers.indexOf(name)
            val data = columns[index]
            if (data.isEmpty()) {
                throw ImportException("column '$name' is empty")
            }
            NamedDataset(name, data)
        }
    }

    private fun importLong(path: Path, idColumn: String, valueColumn: String): List<NamedDataset> {
        val all = CSVUtil.readRowsToListOfStringArrays(path)
        if (all.isEmpty()) {
            throw ImportException("file ${path} produced no rows")
        }
        val headers = all[0].toList()
        val rows = if (all.size > 1) all.subList(1, all.size) else emptyList()
        if (rows.isEmpty()) {
            throw ImportException("file ${path} has a header but no data rows")
        }
        return WideLongReshape.splitLong(rows, headers, idColumn, valueColumn)
    }

    private fun fileStem(path: Path): String {
        val name = path.fileName.toString()
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }
}
