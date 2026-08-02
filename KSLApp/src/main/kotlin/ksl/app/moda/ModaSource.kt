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

package ksl.app.moda

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVRecord
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 *  A score a study asked for and did not get.
 */
data class MissingScore(
    val alternative: String,
    val metric: String
) {
    val message: String
        get() = "No score for alternative '$alternative' on metric '$metric'."
}

/**
 *  The scores a source was able to supply, and a note of anything it could not.
 *
 *  What is missing is reported rather than filled in or quietly dropped. An alternative scored on
 *  only some of the metrics cannot be compared with one scored on all of them, so it is left out of
 *  the study, but leaving it out without saying so would make the study look complete when it is
 *  not.
 */
data class ScoreTable(
    val values: Map<String, Map<String, Double>>,
    val missing: List<MissingScore>
) {
    val isComplete: Boolean
        get() = missing.isEmpty()
}

/**
 *  Somewhere a study's scores can be read from.
 */
fun interface ModaSourceIfc {

    /**
     *  The scores for the named [alternatives] on the named [metrics].
     *
     *  Anything asked for and not found is reported in [ScoreTable.missing] rather than raised,
     *  since a study is usually still worth running on the alternatives that are complete.
     */
    fun scores(alternatives: Set<String>, metrics: Set<String>): ScoreTable
}

/**
 *  Something registered by whoever runs a study, to read scores from data this library knows
 *  nothing about.
 */
fun interface ModaSourceProviderIfc {

    /** Builds a source from the parameters a document supplied. */
    fun create(parameters: Map<String, String>): ModaSourceIfc
}

/**
 *  Scores held in the document itself.
 */
class InlineScoreSource(
    private val table: Map<String, Map<String, Double>>
) : ModaSourceIfc {

    override fun scores(alternatives: Set<String>, metrics: Set<String>): ScoreTable {
        val values = mutableMapOf<String, Map<String, Double>>()
        val missing = mutableListOf<MissingScore>()
        for (alternative in alternatives) {
            val row = table[alternative]
            if (row == null) {
                // Nothing at all for this alternative, so every metric is missing for it.
                metrics.sorted().forEach { missing.add(MissingScore(alternative, it)) }
                continue
            }
            val complete = mutableMapOf<String, Double>()
            var whole = true
            for (metric in metrics.sorted()) {
                val score = row[metric]
                if (score == null) {
                    missing.add(MissingScore(alternative, metric))
                    whole = false
                } else {
                    complete[metric] = score
                }
            }
            if (whole) values[alternative] = complete
        }
        return ScoreTable(values, missing)
    }
}

/**
 *  Scores in a delimited text file, one row per alternative.
 *
 *  The file is read through a delimited-text reader rather than by splitting on the delimiter, so
 *  that quoted fields containing the delimiter are handled rather than silently corrupting a row.
 */
class DelimitedFileSource(
    private val path: Path,
    private val alternativeColumn: String,
    private val metricColumns: List<String>,
    private val delimiter: Delimiter
) : ModaSourceIfc {

    override fun scores(alternatives: Set<String>, metrics: Set<String>): ScoreTable {
        require(Files.exists(path)) { "The file '$path' does not exist." }
        val rows = readRows()
        val values = mutableMapOf<String, Map<String, Double>>()
        val missing = mutableListOf<MissingScore>()
        for (alternative in alternatives) {
            val row = rows[alternative]
            if (row == null) {
                metrics.sorted().forEach { missing.add(MissingScore(alternative, it)) }
                continue
            }
            val complete = mutableMapOf<String, Double>()
            var whole = true
            for (metric in metrics.sorted()) {
                // A column that is absent, empty, or not a number is missing rather than zero.
                // Reading an unparseable cell as zero would quietly make an alternative look
                // excellent on a metric where smaller is better.
                val score = row[metric]?.trim()?.toDoubleOrNull()
                if (score == null) {
                    missing.add(MissingScore(alternative, metric))
                    whole = false
                } else {
                    complete[metric] = score
                }
            }
            if (whole) values[alternative] = complete
        }
        return ScoreTable(values, missing)
    }

    /** The file as a row per alternative name, each a map of column name to raw text. */
    private fun readRows(): Map<String, Map<String, String>> {
        val format: CSVFormat = CSVFormat.Builder.create(CSVFormat.DEFAULT)
            .setDelimiter(delimiter.character)
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreSurroundingSpaces(true)
            .setTrim(true)
            .build()
        val rows = LinkedHashMap<String, Map<String, String>>()
        Files.newBufferedReader(path).use { reader ->
            val parser: CSVParser = format.parse(reader)
            parser.use { open ->
                val headers: List<String> = open.headerNames
                require(headers.contains(alternativeColumn)) {
                    "The file '$path' has no column named '$alternativeColumn'. It has: " +
                            headers.joinToString(", ") + "."
                }
                val records: List<CSVRecord> = open.records
                for (record in records) {
                    val name: String = record.get(alternativeColumn).trim()
                    if (name.isEmpty()) continue
                    val row = mutableMapOf<String, String>()
                    for (column in metricColumns) {
                        if (headers.contains(column) && record.isSet(column)) {
                            row[column] = record.get(column)
                        }
                    }
                    // A name appearing twice keeps the first row, so a re-run reads the same study
                    // rather than depending on which duplicate came last.
                    rows.putIfAbsent(name, row)
                }
            }
        }
        return rows
    }
}

/**
 *  Turns the reference a document carries into something scores can be read from.
 *
 *  Whether a reference can be resolved is asked separately from resolving it, so a study can be
 *  checked before it is run and a source that is not going to work is reported alongside every
 *  other problem rather than as the exception that stops the run.
 *
 *  @param documentLocation where the document is, so that relative paths mean what they say. When
 *  absent, relative paths resolve against the working directory.
 *  @param providers sources registered by whoever is running the study, by id
 */
class ModaSourceResolver(
    private val documentLocation: Path? = null,
    private val providers: Map<String, ModaSourceProviderIfc> = emptyMap()
) {

    /**
     *  Why [reference] cannot be turned into a source, or null when it can.
     *
     *  Some kinds of reference are understood but not yet supported. Saying so plainly is better
     *  than a document appearing to be wrong when it is the reader that is incomplete.
     */
    fun resolutionProblem(reference: ModaSourceReference): String? = when (reference) {
        is ModaSourceReference.InlineScores ->
            if (reference.table.isEmpty()) "The document holds no scores." else null

        is ModaSourceReference.DelimitedFile -> {
            val resolved = resolvePath(reference.path)
            when {
                reference.metricColumns.isEmpty() -> "No metric columns were named for '${reference.path}'."
                !Files.exists(resolved) -> "The file '$resolved' does not exist."
                !Files.isReadable(resolved) -> "The file '$resolved' cannot be read."
                else -> null
            }
        }

        is ModaSourceReference.RegisteredProvider ->
            if (providers.containsKey(reference.providerId)) null
            else "No source provider is registered under '${reference.providerId}'." +
                    if (providers.isEmpty()) " None are registered."
                    else " Registered: ${providers.keys.sorted().joinToString(", ")}."

        is ModaSourceReference.KslDatabase ->
            "Reading scores from a KSL database is not available yet. Export the responses to a " +
                    "delimited file, or hold them in the document, until it is."

        is ModaSourceReference.RetainedRun ->
            "Reading scores from a retained run is not available yet. Export the responses to a " +
                    "delimited file, or hold them in the document, until it is."
    }

    /** Indicates whether [reference] can be turned into a source. */
    fun canResolve(reference: ModaSourceReference): Boolean = resolutionProblem(reference) == null

    /**
     *  Turns [reference] into something scores can be read from.
     *
     *  @throws IllegalArgumentException if it cannot be, carrying the same explanation
     *  [resolutionProblem] gives
     */
    fun resolve(reference: ModaSourceReference): ModaSourceIfc {
        val problem = resolutionProblem(reference)
        require(problem == null) { problem!! }
        return when (reference) {
            is ModaSourceReference.InlineScores -> InlineScoreSource(reference.table)
            is ModaSourceReference.DelimitedFile -> DelimitedFileSource(
                resolvePath(reference.path),
                reference.alternativeColumn,
                reference.metricColumns,
                reference.delimiter
            )
            is ModaSourceReference.RegisteredProvider ->
                providers[reference.providerId]!!.create(reference.parameters)
            is ModaSourceReference.KslDatabase, is ModaSourceReference.RetainedRun ->
                throw IllegalArgumentException(resolutionProblem(reference))
        }
    }

    /** A relative path means relative to the document, so a study and its data travel together. */
    fun resolvePath(path: String): Path {
        val given = Paths.get(path)
        if (given.isAbsolute) return given
        val base = documentLocation ?: return given
        val directory = if (Files.isDirectory(base)) base else base.parent
        return directory?.resolve(given)?.normalize() ?: given
    }
}
