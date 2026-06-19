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

package ksl.service.capability.run

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ksl.service.config.ServerConfig
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Auto-detects bundle JARs dropped into a directory (`~/.ksl/bundles/` by
 * convention) and keeps a [BundleRegistry] in sync — the human-initiated dynamic
 * catalog (Phase 8 plan §4.4 / §6). It polls the directory on an interval,
 * diffing by content SHA-256 so a *rebuilt* jar (same name, new bytes) is
 * detected, not just new files. A removed jar drops its bundles.
 *
 * Polling (not `WatchService`) is deliberate: cross-platform, predictable, and
 * it reuses the content hash we already track. [scanOnce] is the unit of work
 * and is directly testable.
 */
class BundleDirectoryWatcher(
    private val registry: BundleRegistry,
    private val dir: Path,
    private val interval: Duration = 5.seconds,
) {
    // path -> last-seen file content hash, so unchanged jars are skipped.
    private val known = mutableMapOf<Path, String>()

    /** Launches the polling loop on [scope]; returns the [Job] for cancellation. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            runCatching { scanOnce() }
            delay(interval)
        }
    }

    /** One reconciliation pass: load new/changed jars, drop removed ones. */
    @Synchronized
    fun scanOnce() {
        val current = currentJars()
        for ((path, hash) in current) {
            if (known[path] != hash) {
                runCatching { registry.loadOrReplaceFromJar(path) }
                known[path] = hash
            }
        }
        for (path in known.keys - current.keys) {
            runCatching { registry.removeFromJar(path) }
        }
        known.keys.retainAll(current.keys)
    }

    private fun currentJars(): Map<Path, String> {
        if (!Files.isDirectory(dir)) return emptyMap()
        return Files.list(dir).use { stream ->
            stream
                .filter { it.toString().endsWith(".jar") && Files.isRegularFile(it) }
                .toList()
                .associateWith { fileHash(it) }
        }
    }

    private fun fileHash(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }

    companion object {
        /**
         * The bundle directory a server watches, resolved through [ServerConfig]
         * (`KSL_BUNDLES_DIR` > config file > `~/.ksl/bundles/`), created if absent.
         * Kept as a convenience for callers that have no loaded config.
         */
        fun defaultDir(): Path = ServerConfig().bundlesDir()
    }
}
