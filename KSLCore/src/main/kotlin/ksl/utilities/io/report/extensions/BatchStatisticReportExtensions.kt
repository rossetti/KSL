/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.utilities.io.report.extensions

import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report
import ksl.utilities.statistic.BatchStatistic

/**
 * DSL extension functions on [ReportBuilder] for rendering [BatchStatistic] instances.
 *
 * [BatchStatistic] implements [ksl.utilities.statistic.StatisticIfc] (via
 * [ksl.utilities.statistic.AbstractStatistic]), so the statistics on its batch means
 * flow naturally through [ksl.utilities.io.report.ast.ReportNode.StatTable]. This
 * extension adds the batch-specific configuration metadata that [StatisticIfc] does
 * not expose — minimum batch size, maximum batches, rebatch count, total observations,
 * and unbatched remainder.
 */

/**
 * Appends a self-contained section that reports both the batch configuration and the
 * statistics on batch means for a single [BatchStatistic].
 *
 * **Produces (inside a section titled [caption] or [bs.name][BatchStatistic.name]):**
 * 1. A two-column `DataTable` labelled "Batch Configuration" with the batch parameters.
 * 2. A `StatPropertyTable` labelled "Statistics on Batch Means" — a vertical property sheet.
 *
 * Usage:
 * ```kotlin
 * val doc = report("Steady-State Queue Length") {
 *     batchStatistic(batchStat, confidenceLevel = 0.90)
 * }
 * ```
 *
 * @param bs              the batch statistic to report
 * @param caption         optional section title; defaults to [bs.name][BatchStatistic.name]
 * @param confidenceLevel confidence level for half-width and CI columns; must be in (0, 1)
 */
/**
 * Builds a [ReportNode.Document] whose default content is the full batch-statistic
 * section (batch configuration table and statistics on batch means).
 *
 * Zero-code path:
 * ```kotlin
 * myBatchStat.toReport().showInBrowser()
 * myBatchStat.toReport().writeMarkdown()
 * ```
 *
 * Custom block replaces the default:
 * ```kotlin
 * myBatchStat.toReport("Queue Length Analysis") {
 *     batchStatistic(this@toReport)         // standard section
 *     paragraph("Confidence interval is tight enough for decision.")
 * }
 * ```
 *
 * @param title           document title; defaults to [BatchStatistic.name]
 * @param confidenceLevel confidence level for half-width and CI columns; must be in (0, 1)
 * @param block           optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun BatchStatistic.toReport(
    title: String = name,
    confidenceLevel: Double = 0.95,
    block: ReportBuilder.() -> Unit = { batchStatistic(this@toReport, confidenceLevel = confidenceLevel) }
): ReportNode.Document = report(title, block)

fun ReportBuilder.batchStatistic(
    bs: BatchStatistic,
    caption: String? = null,
    confidenceLevel: Double = 0.95
) {
    section(caption ?: bs.name) {
        dataTable(
            headers = listOf("Parameter", "Value"),
            rows = listOf(
                listOf("Min Batches",          bs.minNumBatches.toString()),
                listOf("Min Batch Size",       bs.minBatchSize.toString()),
                listOf("Max Batches Multiple", bs.minNumBatchesMultiple.toString()),
                listOf("Max Batches",          bs.maxNumBatches.toString()),
                listOf("Current Batch Size",   bs.currentBatchSize.toString()),
                listOf("Num Batches",          bs.numBatches.toString()),
                listOf("Num Rebatches",        bs.numRebatches.toString()),
                listOf("Total Observations",   bs.totalNumberOfObservations.toLong().toString()),
                listOf("Amount Unbatched",     bs.amountLeftUnbatched.toLong().toString())
            ),
            caption = "Batch Configuration"
        )
        statPropertyTable(
            stat = bs,
            caption = "Statistics on Batch Means",
            confidenceLevel = confidenceLevel
        )
    }
}
