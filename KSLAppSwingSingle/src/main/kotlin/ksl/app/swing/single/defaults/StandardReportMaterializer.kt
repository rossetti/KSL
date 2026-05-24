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

package ksl.app.swing.single.defaults

import ksl.app.session.RunResult
import ksl.utilities.io.dbutil.SimulationSnapshot
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.extensions.snapshotAcrossReplicationStatistics
import ksl.utilities.io.report.extensions.snapshotRunSummary
import ksl.utilities.io.report.extensions.snapshotSimulationFrequencies
import ksl.utilities.io.report.extensions.snapshotSimulationHistograms
import ksl.utilities.io.report.extensions.snapshotSimulationTimeSeries
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.renderer.RenderContext
import ksl.utilities.io.report.writeHtml
import ksl.utilities.io.report.writeMarkdown
import ksl.utilities.io.report.writeText
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * Format identifier for [StandardReportMaterializer.materialize].
 *
 * Limited to the three formats surfaced by the Single-app
 * default result panel's standard buttons.  LaTeX is supported
 * by the framework
 * (`ksl.utilities.io.report.writeLaTeX`) but is exposed only
 * through the *Advanced…* affordance — not in the standard
 * trio.  Per scenario workflow §11 the standard buttons match
 * the formats most analysts reach for first.
 */
enum class StandardReportFormat(val fileExtension: String, val labelForButton: String) {
    HTML("html", "HTML"),
    MARKDOWN("md", "Markdown"),
    TEXT("txt", "Text");

    companion object {
        /** Returns the format whose [labelForButton] matches [name], or null. */
        fun fromButtonLabel(name: String): StandardReportFormat? =
            values().firstOrNull { it.labelForButton.equals(name, ignoreCase = true) }
    }
}

/**
 * Result of a single materialize attempt.
 */
sealed class StandardReportOutcome {
    /** The report rendered successfully; [file] is the written report file. */
    data class Ok(val file: File) : StandardReportOutcome()

    /**
     * Rendering failed.  [reason] is a short human-readable summary
     * suitable for a notification; [cause] is the underlying
     * Throwable when available (null for non-exception failures
     * such as "no snapshot to render").
     */
    data class Failed(val reason: String, val cause: Throwable? = null) : StandardReportOutcome()
}

/**
 * Renders one of the framework's *standard* simulation reports
 * from a terminal [RunResult] into the per-run reports
 * directory under the active workspace.
 *
 * The actual rendering work delegates to the existing
 * `ksl.utilities.io.report` framework:
 *  - Snapshot is extracted from
 *    [RunResult.Completed.snapshot] or
 *    [RunResult.BatchCompleted.snapshots] (first non-null).
 *  - Document is produced via
 *    `SimulationSnapshot.ExperimentCompleted.toReport()`, which
 *    composes run summary + across-replication stats +
 *    histograms + frequencies + time-series stats.
 *  - File is written via
 *    `writeHtml(...)` / `writeMarkdown(...)` / `writeText(...)`
 *    with a `RenderContext` rooted at [reportsDir] so plot
 *    artifacts land alongside the report file.
 *
 * The helper is also reusable by the *Advanced…* dialog (later
 * N-commit) when the user selects "use the standard content
 * with these per-render options" — the customisation overrides
 * the defaults the [toReport] block applies.
 */
object StandardReportMaterializer {

    /** Default file stem (without extension). */
    const val DEFAULT_FILE_STEM: String = "standard"

    /**
     * Renders [result]'s snapshot to [format] under [reportsDir].
     * Creates [reportsDir] (and its parents) if absent.  The
     * report file is named `<fileStem>.<extension>`.
     *
     * @param result the terminal result whose snapshot is rendered.
     *   Must be `Completed` or `BatchCompleted` (with at least
     *   one snapshot); other states return [StandardReportOutcome.Failed].
     * @param format which renderer to invoke.
     * @param reportsDir target directory; created lazily.
     * @param fileStem filename without extension; defaults to
     *   [DEFAULT_FILE_STEM].
     * @return either the written file or a failure with cause.
     */
    /**
     *  Options controlling which sections of the snapshot's standard
     *  report appear in the materialised document.  Defaults match
     *  `SimulationSnapshot.ExperimentCompleted.toReport()`'s built-in
     *  defaults so callers that ignore this parameter get the
     *  pre-existing report content.  The Post-Run Reporting tab
     *  surfaces these as checkboxes; auto-render uses the defaults.
     */
    data class SectionOptions(
        val showRunSummary: Boolean = true,
        val showAcrossReplicationStats: Boolean = true,
        val showHistograms: Boolean = true,
        val showFrequencies: Boolean = true,
        val showTimeSeries: Boolean = true,
        val showDiagnostics: Boolean = false,
        val timeSeriesConfidenceLevel: Double = 0.95
    ) {
        val anySectionSelected: Boolean
            get() = showRunSummary || showAcrossReplicationStats ||
                showHistograms || showFrequencies || showTimeSeries

        companion object {
            val DEFAULT = SectionOptions()
        }
    }

    fun materialize(
        result: RunResult,
        format: StandardReportFormat,
        reportsDir: Path,
        fileStem: String = DEFAULT_FILE_STEM,
        title: String? = null,
        sections: SectionOptions = SectionOptions.DEFAULT
    ): StandardReportOutcome {
        val snapshot = extractSnapshot(result)
            ?: return StandardReportOutcome.Failed("No simulation snapshot available to render.")
        return try {
            if (!reportsDir.exists()) reportsDir.createDirectories()
            val ctx = RenderContext(
                outputDir = reportsDir,
                plotDir = reportsDir.resolve("plots")
            )
            val target = reportsDir.resolve("$fileStem.${format.fileExtension}")
            val doc = buildReport(snapshot, title, sections)
            val file: File = when (format) {
                StandardReportFormat.HTML -> doc.writeHtml(path = target, ctx = ctx)
                StandardReportFormat.MARKDOWN -> doc.writeMarkdown(path = target, ctx = ctx)
                StandardReportFormat.TEXT -> doc.writeText(path = target, ctx = ctx)
            }
            StandardReportOutcome.Ok(file)
        } catch (t: Throwable) {
            StandardReportOutcome.Failed(
                reason = "Rendering ${format.labelForButton} report failed: ${t.message ?: t::class.simpleName ?: "unknown"}",
                cause = t
            )
        }
    }

    /**
     *  Compose a [ksl.utilities.io.report.ast.ReportNode.Document] from
     *  [snapshot] respecting per-section [sections] flags.  When
     *  [title] is `null`, the snapshot's default title is used; when
     *  non-null, it overrides — used by the Single app to inject a
     *  meaningful "Standard Report — <modelName>" title instead of
     *  the bare "Simulation Snapshot — <repRange>" default.
     *
     *  The section flags map 1:1 to the named
     *  `snapshotXxx` extensions in
     *  `ksl.utilities.io.report.extensions.SimulationSnapshotReportExtensions`.
     *  This lets the Post-Run Reporting tab build a custom block that
     *  invokes only the sections the user ticked.  An "everything off"
     *  selection falls back to run summary alone (defensive — the
     *  panel's Save button is disabled in that state, so this branch
     *  is unreachable from the UI).
     */
    private fun buildReport(
        snapshot: SimulationSnapshot.ExperimentCompleted,
        title: String?,
        sections: SectionOptions
    ): ReportNode.Document {
        // `defaultSnapshotTitle` in the report extensions is private,
        // so we fall back to a generic stem here.  Callers that want a
        // meaningful title — like the Single app — pass one explicitly
        // (typically `"Standard Report — <modelName>"`).
        val effectiveTitle = title ?: "Standard Report"
        return snapshot.toReport(
            title = effectiveTitle,
            showPlots = true,
            showTimeSeries = sections.showTimeSeries,
            showDiagnostics = sections.showDiagnostics,
            timeSeriesConfidenceLevel = sections.timeSeriesConfidenceLevel,
            block = {
                if (sections.showRunSummary) {
                    snapshotRunSummary(snapshot.simulationRun)
                }
                if (sections.showAcrossReplicationStats) {
                    snapshotAcrossReplicationStatistics(
                        snapshot.acrossRepStats,
                        showDiagnostics = sections.showDiagnostics
                    )
                }
                if (sections.showHistograms) {
                    snapshotSimulationHistograms(snapshot, showPlot = true)
                }
                if (sections.showFrequencies) {
                    snapshotSimulationFrequencies(snapshot, showPlot = true)
                }
                if (sections.showTimeSeries) {
                    snapshotSimulationTimeSeries(
                        snapshot,
                        confidenceLevel = sections.timeSeriesConfidenceLevel
                    )
                }
                if (!sections.anySectionSelected) {
                    snapshotRunSummary(snapshot.simulationRun)
                }
            }
        )
    }

    /**
     * Extracts the snapshot to render from a terminal [result],
     * or `null` when none is available.  `BatchCompleted` picks
     * the first snapshot in commit order.
     */
    fun extractSnapshot(result: RunResult): SimulationSnapshot.ExperimentCompleted? = when (result) {
        is RunResult.Completed -> result.snapshot
        is RunResult.BatchCompleted -> result.snapshots.firstOrNull()
        else -> null
    }
}
