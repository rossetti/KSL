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

package ksl.app.swing.common.batchreports

import ksl.app.config.ReportFormat
import ksl.app.session.RunResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import ksl.utilities.io.dbutil.AcrossRepStatTableData
import ksl.utilities.io.dbutil.SimulationSnapshot
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.snapshotSimulationResults
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.writeHtml
import ksl.utilities.io.report.writeMarkdown
import ksl.utilities.io.report.writeText
import java.nio.file.Files
import java.nio.file.Path

/**
 *  Reporting layer shared by every app that materialises per-batch
 *  reports (Scenario today; Experiment when its tabs land).
 *
 *  Batches are represented as `RunResult.BatchCompleted`, and each
 *  snapshot in `BatchCompleted.snapshots` is an "item" — the
 *  domain-neutral name covering Scenario-app scenarios, Experiment-app
 *  design points, and anything else a future orchestrator emits.  All
 *  item-name lookups go through the substrate's authoritative
 *  identifier — `ExperimentCompleted.experiment.exp_name` — which the
 *  bridge populates from `model.experimentName`, which the orchestrator
 *  sets from each item's name.
 *
 *  Every render path writes one file per selected [ReportFormat],
 *  then, when HTML was among the formats, asks the platform to open
 *  the HTML file in the user's default browser.
 *
 *  ## File-stem defaults
 *
 *  The default file stems (`scenario-summary-*` and `scenario-summaries`)
 *  preserve the names the Scenario app has been writing to disk, so
 *  existing user files are still recognised by [perItemReportFiles] /
 *  [mostRecentItemFile] after the rename.  New hosts (e.g. Experiment
 *  app) override `itemFileStemPrefix` / `batchFileStem` for
 *  domain-natural names.
 */
object BatchReports {

    /** Result of a render call.  Lets the caller surface per-format
     *  successes and errors in one notification pass. */
    data class WriteOutcome(
        val written: List<Path>,
        val errors: List<String>,
        /** `true` when the renderer deliberately skipped writing because
         *  the user picked [FileHandlingPolicy.SKIP_IF_EXISTS] and a
         *  conflicting file already existed.  Distinct from a failure;
         *  callers should not flip the row state to FAILED on a skip. */
        val skipped: Boolean = false
    )

    /** Policy applied at write time when a destination file already
     *  exists for the target stem.  Picked by the user via the
     *  reports tab's File-handling group; passed through to both
     *  [renderItemSummary] and [renderBatchSummary] on every Generate. */
    enum class FileHandlingPolicy {
        /** Default — write over any existing file with the same stem. */
        OVERWRITE,

        /** Don't write; return an empty [WriteOutcome] with `skipped=true`
         *  when any of the target paths already exists.  Lets the user
         *  Generate selectively without disturbing files they've kept. */
        SKIP_IF_EXISTS,

        /** Append a `_yyyy-MM-dd_HHmmss` suffix to the stem so the new
         *  file lives alongside any prior versions.  Useful when
         *  comparing report variants. */
        APPEND_TIMESTAMP
    }

    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss").withZone(ZoneId.systemDefault())

    /** Suffix used by [FileHandlingPolicy.APPEND_TIMESTAMP] — local
     *  zone, second resolution, filename-safe. */
    private fun timestampSuffix(): String = timestampFormatter.format(Instant.now())

    // ── Lookups ───────────────────────────────────────────────────────────

    /** Item names available for report generation — those that
     *  produced a completed snapshot, in the order the orchestrator
     *  committed them.  Drives GUI pickers. */
    fun availableItemNames(result: RunResult.BatchCompleted): List<String> =
        result.snapshots.map { it.experiment.exp_name }

    /** Lookup a completed snapshot by item name. */
    private fun snapshotFor(
        result: RunResult.BatchCompleted,
        itemName: String
    ): SimulationSnapshot.ExperimentCompleted? =
        result.snapshots.firstOrNull { it.experiment.exp_name == itemName }

    // ── Render entry points ───────────────────────────────────────────────
    //
    // Cross-item reporting (box plot, multiple comparison) lives in the
    // Comparison Analyzer family ([ksl.app.swing.common.comparison]).
    // Only the per-batch summary and per-item summary renderers live
    // here — they're structurally unrelated to comparisons and have no
    // analyzer equivalent.

    /**
     *  **Primary on-demand report.**  One document covering every
     *  completed item (or a caller-supplied subset).  Sections:
     *  1. Run Overview — table with item name, requested reps,
     *     completed reps, run-error flag.
     *  2. Per-item across-replication statistics — one sub-section per
     *     item containing the response × stat table (Count, Mean, Std
     *     Dev, Half-width, CI bounds, Min, Max).
     *
     *  Designed to answer "how did my items stack up?" in one file,
     *  without forcing the analyst to flip between per-item reports.
     *
     *  @param itemNames optional whitelist of `experiment.exp_name`
     *    values to include.  `null` (the default) includes every
     *    snapshot in [result]; a non-null set filters both the Run
     *    Overview rows and the per-item stat sub-sections.  Empty set
     *    means "no items" and surfaces as an error in the returned
     *    [WriteOutcome].  Unknown names in the set are silently
     *    ignored.
     *  @param batchFileStem filename stem (no extension) for the
     *    consolidated document.  Defaults to `"scenario-summaries"` to
     *    preserve Scenario-app filenames; Experiment-app hosts should
     *    pass a domain-natural value such as `"batch-summary"`.
     *  @param reportTitle title that goes on the rendered document.
     *  @param itemTypeNamePlural the human-readable plural form used
     *    in body text (e.g. "scenarios", "design points").
     *  @param itemColumnHeader the Run Overview's leftmost column
     *    header (e.g. "Scenario", "Design Point").
     */
    fun renderBatchSummary(
        result: RunResult.BatchCompleted,
        outputDir: Path,
        formats: Set<ReportFormat>,
        itemNames: Set<String>? = null,
        openHtmlInBrowser: Boolean = true,
        existingFilePolicy: FileHandlingPolicy = FileHandlingPolicy.OVERWRITE,
        batchFileStem: String = "scenario-summaries",
        reportTitle: String = "Batch Summary — ${result.summary.orchestratorName}",
        itemTypeNamePlural: String = "scenarios",
        itemColumnHeader: String = "Scenario"
    ): WriteOutcome {
        if (formats.isEmpty()) {
            return WriteOutcome(emptyList(), listOf("No report formats selected."))
        }
        if (result.snapshots.isEmpty()) {
            return WriteOutcome(
                emptyList(),
                listOf("No completed $itemTypeNamePlural in the most recent run.")
            )
        }
        val included = if (itemNames == null) {
            result.snapshots
        } else {
            result.snapshots.filter { it.experiment.exp_name in itemNames }
        }
        if (included.isEmpty()) {
            return WriteOutcome(
                emptyList(),
                listOf("No $itemTypeNamePlural match the supplied selection.")
            )
        }
        val doc = report(reportTitle) {
            paragraph(
                "Run id ${result.summary.runId}.  " +
                    "${result.summary.completedItems} of ${result.summary.totalItems} " +
                    "$itemTypeNamePlural completed" +
                    if (result.summary.failedItems > 0) ", ${result.summary.failedItems} failed." else "." +
                    if (itemNames != null && included.size < result.snapshots.size) {
                        "  Report filtered to ${included.size} of ${result.snapshots.size} $itemTypeNamePlural."
                    } else ""
            )
            section("Run Overview") {
                dataTable(
                    headers = listOf(itemColumnHeader, "Requested Reps", "Completed Reps", "Run Error"),
                    rows = included.map { snap ->
                        val run = snap.simulationRun
                        val completedReps = run.last_rep_id?.let { it - run.start_rep_id + 1 }
                            ?.toString() ?: ""
                        listOf(
                            snap.experiment.exp_name,
                            run.num_reps.toString(),
                            completedReps,
                            if (run.run_error_msg.isNullOrBlank()) "—" else "Yes ⚠"
                        )
                    }
                )
            }
            section("Across-Replication Statistics — Per $itemColumnHeader") {
                for (snap in included) {
                    section(snap.experiment.exp_name) {
                        if (snap.acrossRepStats.isEmpty()) {
                            paragraph("No across-replication statistics recorded for this ${itemColumnHeader.lowercase()}.")
                        } else {
                            acrossRepStatsTable(snap.acrossRepStats)
                        }
                    }
                }
            }
        }
        val stemDecision = decideStem(batchFileStem, outputDir, formats, existingFilePolicy)
        if (stemDecision.skipped) return stemDecision.toSkippedOutcome("summary")
        return writeAll(doc, outputDir, stemDecision.stem, formats, openHtmlInBrowser)
    }

    /**
     *  Render a single-item summary report for [itemName] using the
     *  substrate's existing `snapshotSimulationResults` pipeline.
     *  Includes everything the snapshot supports: run summary,
     *  across-replication statistics, histograms, frequencies,
     *  time-series period statistics.
     *
     *  @param openHtmlInBrowser when `true` (the default) and HTML is
     *    among [formats], the rendered HTML file is opened in the
     *    user's default browser.  Pass `false` when invoking this in
     *    a batch (e.g. one call per picked item) so only the first
     *    invocation opens a browser tab rather than N.
     *  @param itemFileStemPrefix filename stem prefix (no key, no
     *    extension) for the per-item document.  Defaults to
     *    `"scenario-summary"` to preserve Scenario-app filenames; new
     *    hosts pass a domain-natural value such as `"item-summary"`.
     *  @param reportTitle title that goes on the rendered document.
     */
    fun renderItemSummary(
        result: RunResult.BatchCompleted,
        itemName: String,
        outputDir: Path,
        formats: Set<ReportFormat>,
        openHtmlInBrowser: Boolean = true,
        existingFilePolicy: FileHandlingPolicy = FileHandlingPolicy.OVERWRITE,
        itemFileStemPrefix: String = "scenario-summary",
        reportTitle: String = "Item Summary — $itemName"
    ): WriteOutcome {
        if (formats.isEmpty()) {
            return WriteOutcome(emptyList(), listOf("No report formats selected."))
        }
        val snapshot = snapshotFor(result, itemName) ?: return WriteOutcome(
            written = emptyList(),
            errors = listOf("'$itemName' has no completed snapshot.")
        )
        val doc = report(title = reportTitle) {
            snapshotSimulationResults(snapshot)
        }
        val baseStem = fileStem(itemFileStemPrefix, itemName)
        val stemDecision = decideStem(baseStem, outputDir, formats, existingFilePolicy)
        if (stemDecision.skipped) return stemDecision.toSkippedOutcome(itemName)
        return writeAll(doc, outputDir, stemDecision.stem, formats, openHtmlInBrowser)
    }

    /** Outcome of [decideStem] — either a stem to write to, or a
     *  signal that the writer should produce a skipped [WriteOutcome]. */
    private data class StemDecision(val stem: String, val skipped: Boolean) {
        fun toSkippedOutcome(label: String): WriteOutcome = WriteOutcome(
            written = emptyList(),
            errors = listOf("Skipped '$label' — a matching file already exists (policy: Skip if exists)."),
            skipped = true
        )
    }

    /** Resolve the final stem to write based on [policy].  Returns
     *  the base stem unchanged for [FileHandlingPolicy.OVERWRITE];
     *  signals skip when the policy is SKIP_IF_EXISTS and any of the
     *  target paths already exists; appends a timestamp suffix when
     *  the policy is APPEND_TIMESTAMP. */
    private fun decideStem(
        baseStem: String,
        outputDir: Path,
        formats: Set<ReportFormat>,
        policy: FileHandlingPolicy
    ): StemDecision = when (policy) {
        FileHandlingPolicy.OVERWRITE -> StemDecision(baseStem, skipped = false)
        FileHandlingPolicy.SKIP_IF_EXISTS -> {
            val anyExists = formats.any { fmt ->
                Files.exists(outputDir.resolve("$baseStem.${extension(fmt)}"))
            }
            if (anyExists) StemDecision(baseStem, skipped = true)
            else StemDecision(baseStem, skipped = false)
        }
        FileHandlingPolicy.APPEND_TIMESTAMP -> StemDecision("${baseStem}_${timestampSuffix()}", skipped = false)
    }

    // ── Artifact-path helpers (used by the tab panel to surface       ───
    // ── existing files without re-rendering)                          ───

    /**
     *  All files in [reportsDir] that look like a per-item report for
     *  [itemName] — both the base-stem form and any timestamped
     *  variants written under [FileHandlingPolicy.APPEND_TIMESTAMP].
     *  Used by the tab's Status / Open / Delete affordances.
     *
     *  Returns an empty list when the directory doesn't exist or no
     *  matching files are present.  Order is filesystem-dependent;
     *  callers that need a specific ordering should sort.
     */
    fun perItemReportFiles(
        reportsDir: Path,
        itemName: String,
        itemFileStemPrefix: String = "scenario-summary"
    ): List<Path> =
        listMatchingFiles(reportsDir, fileStem(itemFileStemPrefix, itemName))

    /** As [perItemReportFiles] but for the consolidated batch summary. */
    fun summaryReportFiles(
        reportsDir: Path,
        batchFileStem: String = "scenario-summaries"
    ): List<Path> =
        listMatchingFiles(reportsDir, batchFileStem)

    /**
     *  Most-recently-modified file from [perItemReportFiles], or `null`
     *  when none exist.  This is what the tab opens when the user
     *  clicks a row's Open button.
     */
    fun mostRecentItemFile(
        reportsDir: Path,
        itemName: String,
        itemFileStemPrefix: String = "scenario-summary"
    ): Path? =
        perItemReportFiles(reportsDir, itemName, itemFileStemPrefix)
            .maxByOrNull { Files.getLastModifiedTime(it).toMillis() }

    /** As [mostRecentItemFile] but for the consolidated batch summary. */
    fun mostRecentSummaryFile(
        reportsDir: Path,
        batchFileStem: String = "scenario-summaries"
    ): Path? =
        summaryReportFiles(reportsDir, batchFileStem)
            .maxByOrNull { Files.getLastModifiedTime(it).toMillis() }

    /** Scan [reportsDir] for files whose names match
     *  `<baseStem>` + optional `_<timestamp>` + `.{html,md,txt}`. */
    private fun listMatchingFiles(reportsDir: Path, baseStem: String): List<Path> {
        if (!Files.exists(reportsDir)) return emptyList()
        val pattern = Regex(
            "^${Regex.escape(baseStem)}(_\\d{4}-\\d{2}-\\d{2}_\\d{6})?\\.(html|md|txt)$"
        )
        return try {
            Files.list(reportsDir).use { stream ->
                stream.filter { pattern.matches(it.fileName.toString()) }.toList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Filesystem extension for [format] — matches what [writeAll] uses. */
    private fun extension(format: ReportFormat): String = when (format) {
        ReportFormat.HTML -> "html"
        ReportFormat.MARKDOWN -> "md"
        ReportFormat.TEXT -> "txt"
    }

    // ── DSL helpers ───────────────────────────────────────────────────────

    /** Across-rep stats table used by the consolidated batch summary.
     *  Mirrors the columns in [snapshotSimulationResults]'s default
     *  rendering but inlined here so we can drop the per-item
     *  run-summary section (the Run Overview table at the top of the
     *  document covers the bookkeeping already). */
    private fun ksl.utilities.io.report.dsl.ReportBuilder.acrossRepStatsTable(
        stats: List<AcrossRepStatTableData>
    ) {
        dataTable(
            headers = listOf(
                "Response", "Count", "Mean", "Std Dev",
                "Half-width", "CI Lower", "CI Upper", "Min", "Max"
            ),
            rows = stats.sortedBy { it.stat_name }.map { row ->
                listOf(
                    row.stat_name,
                    fmtInt(row.stat_count),
                    fmt(row.average),
                    fmt(row.std_dev),
                    fmt(row.half_width),
                    ci(row.average, row.half_width, subtract = true),
                    ci(row.average, row.half_width, subtract = false),
                    fmt(row.minimum),
                    fmt(row.maximum)
                )
            }
        )
    }

    private fun fmt(v: Double?, digits: Int = 4): String =
        v?.takeIf { !it.isNaN() && !it.isInfinite() }?.let { "%.${digits}f".format(it) } ?: ""

    private fun fmtInt(v: Double?): String =
        v?.takeIf { !it.isNaN() && !it.isInfinite() }?.toLong()?.toString() ?: ""

    private fun ci(mean: Double?, hw: Double?, subtract: Boolean): String {
        if (mean == null || hw == null) return ""
        if (mean.isNaN() || hw.isNaN()) return ""
        val v = if (subtract) mean - hw else mean + hw
        return fmt(v)
    }

    // ── File writing + browser open ──────────────────────────────────────

    /** Writes [doc] in every format in [formats] using [stem] as the
     *  filename base.  When the formats include HTML *and* the HTML
     *  write succeeded *and* [openHtmlInBrowser] is `true`, also asks
     *  the platform to open the HTML file in the user's default
     *  browser.  Callers writing a batch of reports pass
     *  `openHtmlInBrowser = false` for all but the first call so the
     *  user doesn't get N browser tabs. */
    private fun writeAll(
        doc: ReportNode.Document,
        outputDir: Path,
        stem: String,
        formats: Set<ReportFormat>,
        openHtmlInBrowser: Boolean = true
    ): WriteOutcome {
        Files.createDirectories(outputDir)
        val written = mutableListOf<Path>()
        val errors = mutableListOf<String>()
        var htmlPath: Path? = null
        for (fmt in formats) {
            try {
                val ext = when (fmt) {
                    ReportFormat.HTML -> "html"
                    ReportFormat.MARKDOWN -> "md"
                    ReportFormat.TEXT -> "txt"
                }
                val path = outputDir.resolve("$stem.$ext")
                when (fmt) {
                    ReportFormat.HTML -> {
                        doc.writeHtml(path = path)
                        htmlPath = path
                    }
                    ReportFormat.MARKDOWN -> doc.writeMarkdown(path = path)
                    ReportFormat.TEXT -> doc.writeText(path = path)
                }
                written.add(path)
            } catch (t: Throwable) {
                errors.add("${fmt.name}: ${t.message ?: t::class.simpleName ?: "unknown error"}")
            }
        }
        if (htmlPath != null && openHtmlInBrowser) {
            try {
                openInBrowser(htmlPath)
            } catch (t: Throwable) {
                errors.add("Browser open: ${t.message ?: t::class.simpleName ?: "unknown error"}")
            }
        }
        return WriteOutcome(written, errors)
    }

    /** Open [htmlPath] in the user's default browser via
     *  [java.awt.Desktop].  Bypasses the substrate's
     *  `ReportNode.Document.showInBrowser()` because that writes its
     *  own temp file rather than opening the one we already wrote. */
    private fun openInBrowser(htmlPath: Path) {
        if (!java.awt.Desktop.isDesktopSupported()) {
            throw UnsupportedOperationException("Desktop browser open is not supported on this platform.")
        }
        val desktop = java.awt.Desktop.getDesktop()
        if (!desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
            throw UnsupportedOperationException("Browser action is not supported on this platform.")
        }
        desktop.browse(htmlPath.toUri())
    }

    /** Stable filesystem-safe filename stem so re-renders overwrite
     *  cleanly instead of accumulating versioned files. */
    private fun fileStem(prefix: String, key: String): String {
        val sanitised = key.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
        return "$prefix-$sanitised"
    }
}
