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

package ksl.service.capability.report

import kotlinx.serialization.Serializable

/**
 * A post-run report request for a single run: which reports to render from the
 * run's capture output, and how. This is an ephemeral *reporting* directive that
 * rides the run-submission envelope — distinct from the *capture* toggles in
 * `OutputConfig` (which decide what data the run records). A run with no
 * `OutputConfig` capture enabled simply yields no reports here.
 *
 * @property welch render the Welch warm-up report when non-null and Welch data
 *   was captured.
 * @property trace render the response-trace report when non-null and trace data
 *   was captured.
 */
@Serializable
data class ReportRequest(
    val welch: WelchReport? = null,
    val trace: TraceReport? = null,
) {
    /** True when nothing is requested (the common case — no post-run reporting). */
    val isEmpty: Boolean get() = welch == null && trace == null
}

/**
 * Welch warm-up report options. [formats] names the output formats ("HTML",
 * "Markdown", "Text"; HTML by default). The remaining flags mirror the
 * `welchAnalysis` report sections; [deletionPoint] of -1 lets the report use the
 * full series rather than an MSER recommendation.
 */
@Serializable
data class WelchReport(
    val formats: List<String> = listOf("HTML"),
    val includePartialSums: Boolean = false,
    val includeBiasTest: Boolean = true,
    val includeBatchMeans: Boolean = false,
    val deletionPoint: Int = -1,
)

/**
 * Response-trace report options. [formats] names the output formats ("HTML",
 * "Markdown", "Text"; HTML by default). [repNums] selects which replications to
 * plot (null = the first recorded replication of each trace); [startTime] and
 * [endTime] bound the time window.
 */
@Serializable
data class TraceReport(
    val formats: List<String> = listOf("HTML"),
    val repNums: List<Int>? = null,
    val startTime: Double = 0.0,
    val endTime: Double = Double.MAX_VALUE,
)
