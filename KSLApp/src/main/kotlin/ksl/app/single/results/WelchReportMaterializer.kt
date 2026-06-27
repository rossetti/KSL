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

package ksl.app.single.results

import ksl.observers.welch.WelchDataFileAnalyzer
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.welchAnalysis
import ksl.utilities.io.report.renderer.RenderContext
import ksl.utilities.io.report.writeHtml
import ksl.utilities.io.report.writeMarkdown
import ksl.utilities.io.report.writeText
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * Renders a Welch warm-up (initialization-bias) report from on-disk Welch
 * data files into a per-run reports directory.
 *
 * Unlike [StandardReportMaterializer], which consumes an in-memory
 * post-run snapshot, Welch analysis is captured *during* the run by
 * `ksl.observers.welch.WelchFileObserver`s (attached by the orchestrator
 * when `ksl.app.config.OutputConfig.enableWelchAnalysis` is on).  Each
 * observer streams its response's observations to
 * `<outputDirectory>/<responseName>_Welch/` as `.wdf` (binary data) and
 * `.json` (metadata) files.  This materializer reloads those files —
 * the live observer objects are not needed — and drives the existing
 * `ksl.utilities.io.report.extensions.welchAnalysis` DSL to produce one
 * report section per response.
 *
 * Reuses [StandardReportFormat] and [StandardReportOutcome] so a host's
 * Save handler and "recent saves" plumbing stay symmetric with the
 * standard-report path.
 */
object WelchReportMaterializer {

    /** Default file stem (without extension). */
    const val DEFAULT_FILE_STEM: String = "welch"

    /** Suffix marking a per-response Welch capture subdirectory. */
    private const val WELCH_DIR_SUFFIX: String = "_Welch"

    /**
     * Options controlling which optional sections appear for each
     * response.  The Welch + cumulative-average plot section is always
     * present; these toggles add the partial-sums plot, the Schruben
     * bias test, and the post-deletion batch-means analysis.  Defaults
     * match the `welchAnalysis` DSL's own defaults.
     *
     * @property includePartialSums include the partial-sums plot section.
     * @property includeBiasTest include the Schruben initialization-bias
     *   test section.
     * @property includeBatchMeans include the post-deletion batch-means
     *   analysis section.
     * @property deletionPoint warm-up deletion point for the batch-means
     *   and bias-test sections; -1 selects the MSER recommendation.
     */
    data class Options(
        val includePartialSums: Boolean = true,
        val includeBiasTest: Boolean = false,
        val includeBatchMeans: Boolean = false,
        val deletionPoint: Int = -1
    )

    /**
     * Locates every `.json` metadata file inside a `<name>_Welch`
     * subdirectory directly under [outputDir] and reconstructs a
     * `WelchDataFileAnalyzer` from each, sorted by file path for
     * deterministic section order.
     *
     * Per-file parse failures are skipped defensively: a stray or
     * malformed `.json` in a `_Welch` directory must not break a UI
     * enablement probe, and the materializer simply reports fewer
     * sections.  Returns an empty list when [outputDir] does not exist or
     * contains no Welch data.
     *
     * @param outputDir the run output directory the orchestrator wrote to
     *   (the same path that hosts [ksl.observers.welch.WelchFileObserver]
     *   subdirectories).
     * @return the discovered analyzers, ordered deterministically.
     */
    fun discoverAnalyzers(outputDir: Path): List<WelchDataFileAnalyzer> {
        if (!outputDir.exists() || !Files.isDirectory(outputDir)) return emptyList()
        val jsonPaths = mutableListOf<Path>()
        Files.newDirectoryStream(outputDir).use { dirs ->
            for (dir in dirs) {
                if (!Files.isDirectory(dir)) continue
                if (!dir.fileName.toString().endsWith(WELCH_DIR_SUFFIX)) continue
                Files.newDirectoryStream(dir, "*.json").use { jsons ->
                    for (json in jsons) jsonPaths.add(json)
                }
            }
        }
        jsonPaths.sortBy { it.toAbsolutePath().normalize().toString() }
        return jsonPaths.mapNotNull { path ->
            runCatching { WelchDataFileAnalyzer.makeFromJSON(path) }.getOrNull()
        }
    }

    /**
     * Deletes every immediate `<name>_Welch` capture subdirectory under
     * [outputDir], leaving all other run output (csvDir, dbDir,
     * kslOutput.txt, plotDir, …) untouched.  Called before a Simulate so a
     * fresh run's Welch discovery never mixes in a prior run's data.
     *
     * @param outputDir the run output directory to clean.
     * @return the number of `_Welch` directories removed (0 when none exist
     *   or [outputDir] is absent).
     */
    fun clearWelchData(outputDir: Path): Int {
        if (!outputDir.exists() || !Files.isDirectory(outputDir)) return 0
        var removed = 0
        Files.newDirectoryStream(outputDir).use { dirs ->
            for (dir in dirs) {
                if (!Files.isDirectory(dir)) continue
                if (!dir.fileName.toString().endsWith(WELCH_DIR_SUFFIX)) continue
                if (dir.toFile().deleteRecursively()) removed++
            }
        }
        return removed
    }

    /**
     * Builds one document with a `welchAnalysis` section per analyzer in
     * [analyzers] and writes it to `<reportsDir>/<fileStem>.<ext>`, with
     * any embedded plot images under `<reportsDir>/plots`.  Creates
     * [reportsDir] (and parents) if absent.
     *
     * @param analyzers the analyzers to report from, in section order
     *   (typically the result of [discoverAnalyzers]).  An empty list
     *   yields [StandardReportOutcome.Failed] — there is nothing to render.
     * @param format which renderer to invoke.
     * @param reportsDir target directory; created lazily.
     * @param fileStem filename without extension; defaults to
     *   [DEFAULT_FILE_STEM].
     * @param title document title; when null, "Warm-Up Analysis" is used.
     * @param options optional-section toggles applied to every response.
     * @return either the written file or a failure with cause.
     */
    fun materialize(
        analyzers: List<WelchDataFileAnalyzer>,
        format: StandardReportFormat,
        reportsDir: Path,
        fileStem: String = DEFAULT_FILE_STEM,
        title: String? = null,
        options: Options = Options()
    ): StandardReportOutcome {
        if (analyzers.isEmpty()) {
            return StandardReportOutcome.Failed("No Welch warm-up data available to render.")
        }
        return try {
            if (!reportsDir.exists()) reportsDir.createDirectories()
            val ctx = RenderContext(
                outputDir = reportsDir,
                plotDir = reportsDir.resolve("plots")
            )
            val target = reportsDir.resolve("$fileStem.${format.fileExtension}")
            val doc = report(title ?: "Warm-Up Analysis") {
                for (analyzer in analyzers) {
                    welchAnalysis(
                        analyzer = analyzer,
                        includePartialSums = options.includePartialSums,
                        includeBatchMeans = options.includeBatchMeans,
                        includeBiasTest = options.includeBiasTest,
                        // The app never shows the MSER deletion-point table; this
                        // also avoids the O(n^2) MSER computation unless the bias
                        // test is on (which needs the deletion point).
                        includeDeletionPoint = false,
                        deletionPoint = options.deletionPoint
                    )
                }
            }
            val file: File = when (format) {
                StandardReportFormat.HTML -> doc.writeHtml(path = target, ctx = ctx)
                StandardReportFormat.MARKDOWN -> doc.writeMarkdown(path = target, ctx = ctx)
                StandardReportFormat.TEXT -> doc.writeText(path = target, ctx = ctx)
            }
            StandardReportOutcome.Ok(file)
        } catch (t: Throwable) {
            StandardReportOutcome.Failed(
                reason = "Rendering ${format.labelForButton} Welch report failed: " +
                    (t.message ?: t::class.simpleName ?: "unknown"),
                cause = t
            )
        }
    }
}
