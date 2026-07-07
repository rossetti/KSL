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

import java.nio.file.Files
import java.nio.file.Path

/** A saved document reference — its kind, the name it was saved under, its byte size, and its path. */
data class DocumentRef(val kind: String, val name: String, val bytes: Long, val path: String)

/**
 * A durable, **stateless** named-document store for the server: save/load/list/delete the config and
 * layout documents a user (or LLM) authors, so they survive across sessions and the user can restart
 * work — the server analogue of the desktop apps' `configs/` folder.
 *
 * It is *not* a session: every call is independent (save writes a file, load reads it, list scans a
 * directory), so it does not introduce server-side session lifecycle — the same durable-store pattern
 * as [ArtifactStore]/[ResultStore]. Documents live at `<root>/<kind>/<name>`; in the server [root] is
 * `<workspace>/KSLServer/documents/` (beside the run artifacts).
 */
class DocumentStore(private val root: Path = ResultStore.defaultDir().resolveSibling("documents")) {

    /** Saves [content] under ([kind], [name]) (overwriting), returning the file path. */
    fun save(kind: String, name: String, content: String): Path {
        val dir = Files.createDirectories(root.resolve(safe(kind)))
        val file = dir.resolve(safe(name))
        Files.writeString(file, content)
        return file
    }

    /** The saved content for ([kind], [name]), or null if none. */
    fun load(kind: String, name: String): String? {
        val file = fileFor(kind, name)
        return if (Files.isRegularFile(file)) Files.readString(file) else null
    }

    /** Deletes ([kind], [name]); true if a document was removed. */
    fun delete(kind: String, name: String): Boolean = Files.deleteIfExists(fileFor(kind, name))

    /** Every saved document, or only those of [kind] when given (sorted by kind then name). */
    fun list(kind: String? = null): List<DocumentRef> {
        if (!Files.isDirectory(root)) return emptyList()
        val kindDirs = when {
            kind != null -> listOf(root.resolve(safe(kind))).filter { Files.isDirectory(it) }
            else -> Files.list(root).use { s -> s.filter { Files.isDirectory(it) }.toList() }
        }
        return kindDirs.flatMap { kd ->
            Files.list(kd).use { s ->
                s.filter { Files.isRegularFile(it) }.map { f ->
                    DocumentRef(
                        kind = kd.fileName.toString(),
                        name = f.fileName.toString(),
                        bytes = runCatching { Files.size(f) }.getOrDefault(0L),
                        path = f.toString(),
                    )
                }.toList()
            }
        }.sortedWith(compareBy({ it.kind }, { it.name }))
    }

    private fun fileFor(kind: String, name: String): Path = root.resolve(safe(kind)).resolve(safe(name))

    /** Keeps a kind/name filesystem-safe (and prevents path traversal). */
    private fun safe(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "unnamed" }
}
