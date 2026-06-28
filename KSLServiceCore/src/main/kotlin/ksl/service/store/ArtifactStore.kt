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

package ksl.service.store

import ksl.service.capability.run.dto.ArtifactRef
import java.nio.file.Files
import java.nio.file.Path

/**
 * The on-disk home for a result's rendered artifacts (reports, plot images,
 * exports). A sibling of `ResultStore`: artifacts for a result live under
 * `<root>/<resultId>/artifacts/`, beside that result's `result.json`, so the
 * two share one directory per result and the `ResultStore` retention sweep
 * (which deletes a result's directory wholesale) evicts the artifacts with it.
 *
 * Phase A delivers only the pipe: a place to write per-result files and a
 * read-only listing/resolution surface the transports serve. The capabilities
 * that *write* artifacts (Welch/trace materialization, comparison reports) land
 * in later phases. Path resolution is normalized and confined to the artifacts
 * directory, so a hostile artifact name cannot escape the root.
 *
 * @param root the cache root shared with `ResultStore` (defaults to
 *   [ResultStore.defaultDir]); servers pass `ServerConfig.resultCacheDir()`.
 */
class ArtifactStore(private val root: Path = ResultStore.defaultDir()) {

    /** The artifacts directory for [resultId], created on demand. */
    fun dirFor(resultId: String): Path =
        Files.createDirectories(artifactsDir(resultId))

    /**
     * The server-owned capture/output directory for [resultId]
     * (`<root>/<resultId>/output`) — where a run's redirected output (Welch/trace
     * files, the KSL database) lands. The single source of truth shared by run
     * submission (which stamps it onto the run's output config) and later
     * discovery (reporting, database analysis). Not created here; the run creates
     * it, and readers tolerate its absence.
     */
    fun outputDirFor(resultId: String): Path =
        root.resolve(resultId).resolve(OUTPUT)

    /**
     * Every artifact recorded for [resultId] as wire refs, ordered by name; an
     * empty list when none have been written (or the result is unknown). Nested
     * files (e.g. `plots/welch.png`) are reported with their relative path as
     * the [ArtifactRef.name].
     */
    fun list(resultId: String): List<ArtifactRef> {
        val dir = artifactsDir(resultId)
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.walk(dir).use { walk ->
            walk.filter { Files.isRegularFile(it) }
                .map { f ->
                    ArtifactRef(
                        name = dir.relativize(f).toString().replace('\\', '/'),
                        mediaType = mediaTypeOf(f),
                        path = f.toString(),
                    )
                }
                .sorted(compareBy { it.name })
                .toList()
        }
    }

    /**
     * Resolves an artifact file by [name] within [resultId]'s artifacts
     * directory, or `null` when it does not exist or would escape the directory
     * (path-traversal guard). [name] may include a nested path (`plots/x.png`).
     */
    fun resolve(resultId: String, name: String): Path? {
        val dir = artifactsDir(resultId).toAbsolutePath().normalize()
        val target = dir.resolve(name).toAbsolutePath().normalize()
        if (!target.startsWith(dir) || !Files.isRegularFile(target)) return null
        return target
    }

    private fun artifactsDir(resultId: String): Path =
        root.resolve(resultId).resolve(ARTIFACTS)

    private fun mediaTypeOf(f: Path): String =
        when (f.fileName.toString().substringAfterLast('.', "").lowercase()) {
            "html" -> "text/html"
            "md" -> "text/markdown"
            "txt" -> "text/plain"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "png" -> "image/png"
            "svg" -> "image/svg+xml"
            "jpg", "jpeg" -> "image/jpeg"
            "tif", "tiff" -> "image/tiff"
            else -> "application/octet-stream"
        }

    private companion object {
        const val ARTIFACTS = "artifacts"
        const val OUTPUT = "output"
    }
}
