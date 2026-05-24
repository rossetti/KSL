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

package ksl.app.swing.experiment

import ksl.app.config.experiment.FactorSpec
import ksl.app.config.experiment.ManualPointSpec
import java.io.BufferedReader
import java.io.File

/**
 *  Outcome of [parseManualCsv].  Either a list of parsed points or
 *  a list of human-readable error messages (everything that could be
 *  flagged in one pass is reported at once, so the user sees the
 *  full picture instead of getting one error per attempt).
 */
sealed class ManualCsvImportResult {
    data class Ok(val points: List<ManualPointSpec>) : ManualCsvImportResult()
    data class Failure(val errors: List<String>) : ManualCsvImportResult()
}

/**
 *  Parse a CSV in the shape that `DesignPointsPreviewDialog`'s
 *  Export produces (and that the Custom design-points tab's Import
 *  consumes).  Shape:
 *
 *  - First line is the header.  Must contain a column for every
 *    declared factor name.  Header columns `#` and `reps` are
 *    optional and recognised by exact name; any other extra
 *    columns are silently ignored.
 *  - Each subsequent non-blank line is one design point.  Cells
 *    are split on commas (no quoting support — KSL factor names
 *    and numbers don't contain commas).
 *  - Each factor value is parsed as `Double` and range-checked
 *    against the factor's `[min, max]` interval.  Values within
 *    range but not equal to a declared level are accepted without
 *    a warning (the import path is for power-user workflows).
 *  - The optional `reps` cell, when present and non-blank, becomes
 *    a per-point replications override; blank means "inherit the
 *    document default".
 *
 *  Extracted as a file-level helper in E7.11 so both the Custom
 *  design-points tab and (any future caller) can re-use the
 *  parsing logic without duplicating it.
 */
fun parseManualCsv(file: File, factors: List<FactorSpec>): ManualCsvImportResult {
    val errors = mutableListOf<String>()
    val lines: List<String> = try {
        file.bufferedReader().use(BufferedReader::readLines)
    } catch (ex: Exception) {
        return ManualCsvImportResult.Failure(
            listOf("could not read ${file.absolutePath}: ${ex.message ?: ex::class.simpleName}")
        )
    }
    if (lines.isEmpty()) {
        return ManualCsvImportResult.Failure(listOf("file is empty"))
    }
    val header = lines[0].split(',').map { it.trim() }
    val nameToCol = header.withIndex().associate { it.value to it.index }
    val factorNames = factors.map { it.name }
    val missing = factorNames.filter { it !in nameToCol }
    if (missing.isNotEmpty()) {
        return ManualCsvImportResult.Failure(
            listOf("header is missing required factor column(s): ${missing.joinToString(", ")}")
        )
    }
    val repsCol = nameToCol["reps"]
    val points = mutableListOf<ManualPointSpec>()
    for ((rowIdx, raw) in lines.drop(1).withIndex()) {
        val lineNo = rowIdx + 2  // 1-based, accounting for the header
        if (raw.isBlank()) continue
        val cells = raw.split(',').map { it.trim() }
        val values = mutableMapOf<String, Double>()
        for (f in factors) {
            val col = nameToCol.getValue(f.name)
            val token = cells.getOrNull(col)
            if (token.isNullOrEmpty()) {
                errors += "line $lineNo: missing value for '${f.name}'"
                continue
            }
            val v = token.toDoubleOrNull()
            if (v == null) {
                errors += "line $lineNo: value for '${f.name}' is not a number: '$token'"
                continue
            }
            val minLvl = f.levels.min()
            val maxLvl = f.levels.max()
            if (v < minLvl || v > maxLvl) {
                errors += "line $lineNo: '${f.name}' value $v is outside " +
                    "the factor's range [$minLvl, $maxLvl]"
                continue
            }
            values[f.name] = v
        }
        val reps: Int? = if (repsCol != null) {
            val token = cells.getOrNull(repsCol)?.trim().orEmpty()
            if (token.isEmpty()) null
            else token.toIntOrNull()?.coerceAtLeast(1)
                ?: run {
                    errors += "line $lineNo: reps token '$token' is not a positive integer"
                    null
                }
        } else null
        if (values.size == factors.size) {
            points += ManualPointSpec(factorValues = values, replications = reps)
        }
    }
    if (errors.isNotEmpty()) return ManualCsvImportResult.Failure(errors)
    if (points.isEmpty()) return ManualCsvImportResult.Failure(listOf("no data rows found"))
    return ManualCsvImportResult.Ok(points)
}
