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

import ksl.observers.ResponseTraceData
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.responseTrace
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
 * Renders a response-trace report from on-disk trace files produced by
 * `ksl.observers.ResponseTrace`s (attached by the orchestrator when
 * `ksl.app.config.OutputConfig.enableResponseTrace` is on).  Each observer
 * streams a response's changes to a `<outputDirectory>/<responseName>_Trace`
 * SQLite file; this materializer reloads those files via
 * [ResponseTraceData] and drives the `responseTrace` reporting DSL.
 *
 * Unlike [StandardReportMaterializer] / [WelchReportMaterializer], a trace
 * file does not record whether its response is time-weighted, so the caller
 * supplies that by handing in already-constructed [ResponseTraceData] objects
 * (typically resolved from a model probe).  [discoverTraceFiles] is provided
 * so a host can decide enablement and cross-reference the on-disk files with
 * the probe.
 *
 * Reuses [StandardReportFormat] / [StandardReportOutcome] so a host's Save
 * handler and "recent saves" plumbing stay symmetric with the other report
 * paths.
 */
object TraceReportMaterializer {

    /** Default file stem (without extension). */
    const val DEFAULT_FILE_STEM: String = "trace"

    /** Suffix marking a per-response trace file. */
    private const val TRACE_SUFFIX: String = "_Trace"

    /**
     * Options controlling which slice of each trace is rendered.  Defaults
     * match the `responseTrace` DSL: the first recorded replication, full
     * time window.
     *
     * @property repNums replications to plot; null selects the first recorded
     *   replication of each trace.
     * @property startTime lower bound of the time window (inclusive).
     * @property endTime upper bound of the time window (inclusive).
     */
    data class Options(
        val repNums: List<Int>? = null,
        val startTime: Double = 0.0,
        val endTime: Double = Double.MAX_VALUE
    )

    /**
     * Every regular `*_Trace` file directly under [outputDir], sorted by path
     * for a deterministic order.  The SQLite aux files (`-wal`, `-shm`,
     * `-journal`) do not match.  Returns an empty list when [outputDir] is
     * absent.
     */
    fun discoverTraceFiles(outputDir: Path): List<Path> {
        if (!outputDir.exists() || !Files.isDirectory(outputDir)) return emptyList()
        val files = mutableListOf<Path>()
        Files.newDirectoryStream(outputDir).use { entries ->
            for (entry in entries) {
                if (Files.isRegularFile(entry) && entry.fileName.toString().endsWith(TRACE_SUFFIX)) {
                    files.add(entry)
                }
            }
        }
        files.sortBy { it.toAbsolutePath().normalize().toString() }
        return files
    }

    /**
     * Builds one `responseTrace` section per entry in [traces] and writes it
     * to `<reportsDir>/<fileStem>.<ext>`, with embedded plot images under
     * `<reportsDir>/plots`.  Creates [reportsDir] (and parents) if absent.
     *
     * @param traces the traces to report, in section order; an empty list
     *   yields [StandardReportOutcome.Failed].
     * @param format which renderer to invoke.
     * @param reportsDir target directory; created lazily.
     * @param fileStem filename without extension; defaults to [DEFAULT_FILE_STEM].
     * @param title document title; when null, "Response Trace" is used.
     * @param options which replications / time window to render.
     * @return either the written file or a failure with cause.
     */
    fun materialize(
        traces: List<ResponseTraceData>,
        format: StandardReportFormat,
        reportsDir: Path,
        fileStem: String = DEFAULT_FILE_STEM,
        title: String? = null,
        options: Options = Options()
    ): StandardReportOutcome {
        if (traces.isEmpty()) {
            return StandardReportOutcome.Failed("No response-trace data available to render.")
        }
        return try {
            if (!reportsDir.exists()) reportsDir.createDirectories()
            val ctx = RenderContext(
                outputDir = reportsDir,
                plotDir = reportsDir.resolve("plots")
            )
            val target = reportsDir.resolve("$fileStem.${format.fileExtension}")
            val doc = report(title ?: "Response Trace") {
                for (trace in traces) {
                    responseTrace(
                        trace,
                        repNums = options.repNums ?: trace.replicationNumbers.take(1),
                        startTime = options.startTime,
                        endTime = options.endTime
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
                reason = "Rendering ${format.labelForButton} trace report failed: " +
                    (t.message ?: t::class.simpleName ?: "unknown"),
                cause = t
            )
        }
    }

    /**
     * Deletes every `*_Trace` file directly under [outputDir] (plus any SQLite
     * `-wal` / `-shm` / `-journal` siblings), leaving all other run output
     * untouched.  Called before a Simulate so a fresh run's trace discovery
     * never mixes in a prior run's data.
     *
     * @param outputDir the run output directory to clean.
     * @return the number of main `*_Trace` files removed.
     */
    fun clearTraceData(outputDir: Path): Int {
        if (!outputDir.exists() || !Files.isDirectory(outputDir)) return 0
        val auxRegex = Regex(".*${Regex.escape(TRACE_SUFFIX)}-(wal|shm|journal)")
        var removed = 0
        Files.newDirectoryStream(outputDir).use { entries ->
            for (entry in entries) {
                if (!Files.isRegularFile(entry)) continue
                val n = entry.fileName.toString()
                if (n.endsWith(TRACE_SUFFIX)) {
                    if (Files.deleteIfExists(entry)) removed++
                } else if (n.matches(auxRegex)) {
                    Files.deleteIfExists(entry)
                }
            }
        }
        return removed
    }
}
