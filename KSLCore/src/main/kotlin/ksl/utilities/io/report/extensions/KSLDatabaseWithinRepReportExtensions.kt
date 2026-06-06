/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.utilities.io.report.extensions

import ksl.utilities.distributions.Normal
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.plotting.HistogramPlot
import ksl.utilities.io.plotting.ObservationsPlot
import ksl.utilities.io.plotting.PPPlot
import ksl.utilities.io.plotting.QQPlot
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report
import ksl.utilities.statistic.Statistic

/**
 * DSL extension functions on [ReportBuilder] for diagnosing a single response's
 * **within-replication** behaviour from data stored in a [KSLDatabase].
 *
 * Where [KSLDatabaseReportExtensions] renders the *stored aggregated* histogram
 * and frequency tables (one record per simulation run), these extensions work
 * from the **raw per-replication observation array** — one value per replication,
 * the across-replication sample — and build the diagnostics an analyst reaches
 * for when judging the across-replication distribution of a response: a
 * histogram, an observations (run-order) plot, and normal Q-Q / P-P plots.
 *
 * All plotting reuses existing KSL plot classes ([HistogramPlot],
 * [ObservationsPlot], [QQPlot], [PPPlot]); no new plot code is introduced and no
 * HTML is hand-built — every figure flows through the report DSL's `plot` leaf.
 *
 * The per-replication values are sourced from
 * `KSLDatabase.replicationDataArraysByExperimentAndResponse`, with any
 * missing-replication `NaN` entries removed.  When an experiment/response pair
 * has no stored data, a notice `Paragraph` is emitted rather than throwing,
 * matching the behaviour of [dbSimulationSummary].
 *
 * **Typical usage:**
 * ```kotlin
 * val db = KSLDatabase("pharmacy.db")
 * db.toWithinReplicationReport("Experiment 1", "SystemTime").showInBrowser()
 * ```
 */

// ── Single-purpose sections ────────────────────────────────────────────────────

/**
 * Appends a "Histogram — [responseName]" section containing an
 * across-replication statistics table for [responseName] and (when [showPlot]
 * is `true`) a [HistogramPlot] built from the per-replication observations.
 *
 * @param db              the database to query
 * @param expName         the experiment whose per-replication values are used
 * @param responseName    the response / time-weighted / counter name
 * @param confidenceLevel confidence level for the statistics table; default 0.95
 * @param showPlot        when `true` (default) include the histogram plot
 */
fun ReportBuilder.dbResponseHistogram(
    db: KSLDatabase,
    expName: String,
    responseName: String,
    confidenceLevel: Double = 0.95,
    showPlot: Boolean = true
) {
    val values = withinReplicationValuesFor(db, expName, responseName)
    section("Histogram — $responseName") {
        if (values.isEmpty()) {
            paragraph(noWithinRepDataMessage(expName, responseName))
        } else {
            emitResponseHistogram(responseName, values, confidenceLevel, showPlot)
        }
    }
}

/**
 * Appends an "Observations — [responseName]" section containing an
 * [ObservationsPlot] of the per-replication values in replication order — useful
 * for spotting run-to-run trends or outliers that a histogram hides.
 *
 * @param db           the database to query
 * @param expName      the experiment whose per-replication values are used
 * @param responseName the response / time-weighted / counter name
 */
fun ReportBuilder.dbResponseObservations(
    db: KSLDatabase,
    expName: String,
    responseName: String
) {
    val values = withinReplicationValuesFor(db, expName, responseName)
    section("Observations — $responseName") {
        if (values.isEmpty()) {
            paragraph(noWithinRepDataMessage(expName, responseName))
        } else {
            emitResponseObservations(responseName, values)
        }
    }
}

/**
 * Appends a "Normality — [responseName]" section containing a normal Q-Q plot
 * and a normal P-P plot of the per-replication values, referenced against a
 * [Normal] distribution fitted by moment matching (sample mean and variance).
 *
 * When the sample variance is not strictly positive (e.g. every replication
 * produced the same value) a notice `Paragraph` replaces the plots, because the
 * reference normal is undefined.
 *
 * @param db           the database to query
 * @param expName      the experiment whose per-replication values are used
 * @param responseName the response / time-weighted / counter name
 */
fun ReportBuilder.dbResponseNormality(
    db: KSLDatabase,
    expName: String,
    responseName: String
) {
    val values = withinReplicationValuesFor(db, expName, responseName)
    section("Normality — $responseName") {
        if (values.isEmpty()) {
            paragraph(noWithinRepDataMessage(expName, responseName))
        } else {
            emitResponseNormality(responseName, values)
        }
    }
}

// ── Composite section ──────────────────────────────────────────────────────────

/**
 * Appends a "Within-Replication Diagnostics — [responseName]" section bundling,
 * in order: an across-replication statistics table, a histogram, an observations
 * plot, and normal Q-Q / P-P plots — the full diagnostic set for one response.
 *
 * The per-replication values are fetched once and shared across every
 * sub-section.  A notice `Paragraph` is emitted (and the remaining content
 * skipped) when no data exists for the pair.
 *
 * @param db              the database to query
 * @param expName         the experiment whose per-replication values are used
 * @param responseName    the response / time-weighted / counter name
 * @param confidenceLevel confidence level for the statistics table; default 0.95
 * @param showPlots       when `true` (default) include all plots
 */
fun ReportBuilder.dbWithinReplicationDiagnostics(
    db: KSLDatabase,
    expName: String,
    responseName: String,
    confidenceLevel: Double = 0.95,
    showPlots: Boolean = true
) {
    section("Within-Replication Diagnostics — $responseName") {
        val values = withinReplicationValuesFor(db, expName, responseName)
        if (values.isEmpty()) {
            paragraph(noWithinRepDataMessage(expName, responseName))
            return@section
        }
        statTable(
            stats = listOf(Statistic(responseName, values)),
            caption = "Across-Replication Statistics: $responseName",
            confidenceLevel = confidenceLevel
        )
        section("Histogram") { emitResponseHistogram(responseName, values, confidenceLevel, showPlots) }
        section("Observations") { if (showPlots) emitResponseObservations(responseName, values) }
        section("Normality") { if (showPlots) emitResponseNormality(responseName, values) }
    }
}

// ── KSLDatabase.toWithinReplicationReport() — zero-code entry point ──────────────

/**
 * Builds a [ReportNode.Document] containing the full within-replication
 * diagnostic set for one response in one experiment stored in this database.
 *
 * This is the within-replication counterpart to the cross-experiment
 * `toComparisonReport` and the single-experiment `toReport` entry points.
 *
 * Zero-code path:
 * ```kotlin
 * val db = KSLDatabase("pharmacy.db")
 * db.toWithinReplicationReport("Experiment 1", "SystemTime").showInBrowser()
 * db.toWithinReplicationReport("Experiment 1", "SystemTime").writeMarkdown()
 * ```
 *
 * @param expName         the experiment to diagnose
 * @param responseName    the response / time-weighted / counter name
 * @param title           document title; defaults to
 *                        "Within-Replication — <responseName> (<expName>)"
 * @param confidenceLevel confidence level for the statistics table; default 0.95
 * @param showPlots       when `true` (default) include all plots
 * @return the assembled [ReportNode.Document]
 */
fun KSLDatabase.toWithinReplicationReport(
    expName: String,
    responseName: String,
    title: String = "Within-Replication — $responseName ($expName)",
    confidenceLevel: Double = 0.95,
    showPlots: Boolean = true
): ReportNode.Document = report(title) {
    dbWithinReplicationDiagnostics(this@toWithinReplicationReport, expName, responseName, confidenceLevel, showPlots)
}

// ── Private content emitters (assume an open section) ───────────────────────────

private fun ReportBuilder.emitResponseHistogram(
    responseName: String,
    values: DoubleArray,
    confidenceLevel: Double,
    showPlot: Boolean
) {
    statTable(
        stats = listOf(Statistic(responseName, values)),
        caption = "Statistics: $responseName",
        confidenceLevel = confidenceLevel
    )
    if (showPlot) {
        val histPlot = HistogramPlot(values)
        histPlot.title = "Histogram — $responseName"
        plot(histPlot, caption = "Histogram — $responseName")
    }
}

private fun ReportBuilder.emitResponseObservations(
    responseName: String,
    values: DoubleArray
) {
    val obsPlot = ObservationsPlot(values, dataName = responseName)
    obsPlot.title = "Observations by Replication — $responseName"
    plot(obsPlot, caption = "Value per Replication — $responseName")
}

private fun ReportBuilder.emitResponseNormality(
    responseName: String,
    values: DoubleArray
) {
    val stat = Statistic(responseName, values)
    if (stat.variance <= 0.0 || stat.count < 2.0) {
        paragraph(
            "A reference normal distribution is undefined for \"$responseName\" — " +
            "the sample variance is not strictly positive (n = ${stat.count.toInt()}, " +
            "variance = ${fmtDouble(stat.variance)})."
        )
        return
    }
    val referenceNormal = Normal(stat.average, stat.variance)
    paragraph(
        "Reference normal fitted by moment matching: mean = ${fmtDouble(stat.average)}, " +
        "variance = ${fmtDouble(stat.variance)}."
    )
    section("Q-Q Plot") {
        val qq = QQPlot(values, referenceNormal)
        qq.title = "Normal Q-Q Plot — $responseName"
        plot(qq, caption = "Normal Q-Q Plot — $responseName")
    }
    section("P-P Plot") {
        val pp = PPPlot(values, referenceNormal)
        pp.title = "Normal P-P Plot — $responseName"
        plot(pp, caption = "Normal P-P Plot — $responseName")
    }
}

// ── Private data access ─────────────────────────────────────────────────────────

/**
 * Returns the per-replication observations for ([expName], [responseName]) with
 * missing-replication NaN entries removed, or an empty array when the pair has no
 * stored data.  Uses the canonical cross-experiment accessor so the same value
 * vector feeds here as feeds the comparison analyses.
 */
private fun withinReplicationValuesFor(
    db: KSLDatabase,
    expName: String,
    responseName: String
): DoubleArray {
    val byResponse = db.replicationDataArraysByExperimentAndResponse()[expName] ?: return DoubleArray(0)
    val raw = byResponse[responseName] ?: return DoubleArray(0)
    return raw.filter { !it.isNaN() }.toDoubleArray()
}

private fun noWithinRepDataMessage(expName: String, responseName: String): String =
    "No within-replication data for \"$responseName\" in experiment \"$expName\"."
