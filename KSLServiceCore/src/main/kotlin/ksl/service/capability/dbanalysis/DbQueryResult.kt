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

package ksl.service.capability.dbanalysis

import kotlinx.serialization.Serializable

/**
 * The outcome of a by-resultId database query. Absence and unmet preconditions
 * are values, not exceptions, so the transports can map them to clear guidance
 * rather than failures.
 *
 * - [NoDatabase] — the run produced no database (the naive-user path).
 * - [Invalid]    — the database exists but the request is not analyzable
 *   (e.g. a comparison with fewer than two experiments); [reason] is the
 *   ready-to-show explanation from `ComparisonSelectionModel.validateForResponse`.
 * - [Json]       — success; [payload] is a JSON document produced by
 *   `DataFrame.toJson()` (no bespoke serialization).
 */
sealed interface DbQueryResult {
    data object NoDatabase : DbQueryResult
    data class Invalid(val reason: String) : DbQueryResult
    data class Json(val payload: String) : DbQueryResult
}

/** Whether a result has an analyzable database, with a human-readable message. */
@Serializable
data class DbStatusDto(
    val present: Boolean,
    val experimentCount: Int,
    val message: String,
)

/** A file-producing database operation (rendered report, data export). Like
 *  [DbQueryResult] but yields artifact file names rather than a JSON payload. */
sealed interface DbReportResult {
    data object NoDatabase : DbReportResult
    data class Invalid(val reason: String) : DbReportResult
    data class Ok(val files: List<String>) : DbReportResult
}

/** Database export target. CSV writes one file per table; EXCEL writes a single workbook. */
enum class DbExportFormat { CSV, EXCEL }

/** The guidance shown when a result has no database to analyze. */
const val NO_DATABASE_MESSAGE: String =
    "No database found for this result. Re-run the model with the database option " +
        "enabled (set enableKSLDatabase in the run's OutputConfig; SQLite is the default)."

/** Default row cap for a statistical-view JSON projection; truncation is reported in the envelope. */
const val DEFAULT_VIEW_ROW_LIMIT: Int = 10_000
