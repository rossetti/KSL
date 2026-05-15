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

package ksl.app.settings

import ksl.simulation.Model
import ksl.utilities.io.OutputDirectory
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * Pure path-resolution helpers for the workspace layout described in
 * scenario workflow §2.  Subdirectories are created lazily — the
 * resolver methods accept a flag controlling whether to create on miss.
 *
 * The workspace is the outer layout under user control; the engine's
 * per-model `OutputDirectory` is the inner layout.  See
 * [bindOutputDirectory] for the bridge that ties them together.
 */
object WorkspaceLayout {

    private val runIdFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneOffset.UTC)

    private val unsafeRunIdChars = Regex("[^A-Za-z0-9._-]")

    /** Resolves `<workspace>/configs/`, optionally creating it. */
    fun configsDir(workspace: Path, createIfMissing: Boolean = false): Path =
        workspace.resolve("configs").maybeCreate(createIfMissing)

    /** Resolves `<workspace>/output/<runId>/`, optionally creating it. */
    fun outputDir(workspace: Path, runId: String, createIfMissing: Boolean = false): Path =
        workspace.resolve("output").resolve(runId).maybeCreate(createIfMissing)

    /** Resolves `<workspace>/reports/<runId>/`, optionally creating it. */
    fun reportsDir(workspace: Path, runId: String, createIfMissing: Boolean = false): Path =
        workspace.resolve("reports").resolve(runId).maybeCreate(createIfMissing)

    /**
     * Builds a sortable, filesystem-safe runId.  When [scenariosFileName]
     * is non-null its stem (no extension) is prepended after sanitization;
     * any character outside `[A-Za-z0-9._-]` is replaced with `_`.
     *
     * Per scenario §2 OQ 5: `<scenariosFileName>-<timestamp>` when a saved
     * file is loaded, `<timestamp>` alone otherwise.
     */
    fun runId(scenariosFileName: String? = null, timestamp: Instant = Instant.now()): String {
        val ts = runIdFormatter.format(timestamp)
        if (scenariosFileName.isNullOrBlank()) return ts
        val stem = scenariosFileName.substringBeforeLast('.', scenariosFileName)
        val safe = unsafeRunIdChars.replace(stem, "_")
        return if (safe.isBlank()) ts else "$safe-$ts"
    }

    /**
     * Abbreviates a path for compact display in a status bar.  Returns
     * the original `toString()` when shorter than [maxLen]; otherwise
     * collapses the leading segments to `~/.../` (or `.../` when not
     * under the user's home) and keeps the last three segments.
     */
    fun abbreviate(path: Path, maxLen: Int = 40, userHome: Path = Path.of(System.getProperty("user.home"))): String {
        val full = path.toString()
        if (full.length <= maxLen) return full
        val n = path.nameCount
        val firstKept = maxOf(0, n - 3)
        val tail = (firstKept until n).joinToString("/") { path.getName(it).toString() }
        val prefix = if (path.startsWith(userHome)) "~/.../" else ".../"
        return prefix + tail
    }

    /**
     * Redirects [model]'s [Model.outputDirectory] to the workspace's
     * per-run output folder.  The engine's `excelDir`, `dbDir`,
     * `csvDir`, `plotDir` subfolders are auto-created underneath by
     * `OutputDirectory`'s constructor — no engine change required.
     *
     * Call site: the per-app view-model right after
     * `ModelBuilderIfc.build(...)` returns the freshly-built model and
     * before submitting it for execution.
     *
     * @param outFileName the engine's stdout-capture filename; defaults
     *   to `"kslOutput.txt"` per scenario §2.
     */
    fun bindOutputDirectory(
        model: Model,
        workspace: Path,
        runId: String,
        outFileName: String = "kslOutput.txt"
    ) {
        model.outputDirectory = OutputDirectory(
            outputDirectoryPath = outputDir(workspace, runId, createIfMissing = true),
            outFileName = outFileName
        )
    }

    private fun Path.maybeCreate(createIfMissing: Boolean): Path {
        if (createIfMissing && !exists()) createDirectories()
        return this
    }
}
