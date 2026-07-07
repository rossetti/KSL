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

package ksl.service.config

import kotlinx.serialization.Serializable
import ksl.app.settings.UserSettingsStore
import ksl.app.settings.WorkspaceLayout
import ksl.service.store.ResultStore
import net.peanuuutz.tomlkt.TomlComment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The server's own configuration as a document — consistent with the
 * document-centric design (Phase 8 plan §6): the server is configured by the
 * same kind of `@Serializable` + TOML artifact its workloads are. One
 * `~/.ksl/config.toml` centralizes the knobs that were otherwise scattered
 * across env vars and hard-coded defaults (the watched bundle dir and poll
 * interval, the result-cache location and retention caps, the listen ports, and
 * the job-concurrency limit).
 *
 * Precedence is **explicit env var > config file > built-in default**: the
 * resolution helpers ([bundlesDir], [resultCacheDir], [mcpPort], [restPort])
 * overlay the matching env var on top of the file value, so an operator can
 * still override a single setting without editing the file. An absent file
 * reproduces the pre-config behavior exactly (every field has a default).
 */
@Serializable
data class ServerConfig(
    val bundles: BundlesConfig = BundlesConfig(),
    val cache: CacheConfig = CacheConfig(),
    val server: ServerSettings = ServerSettings(),
) {
    /**
     * The bundle directories the server discovers, in priority order: the
     * server's own app folder (`KSLWork/KSL_MCP_APPS/bundles/`) first, then the
     * shared workspace folder (`KSLWork/bundles/`) — the same KSLWork layout the
     * desktop apps use, resolved through the user's active workspace. An explicit
     * `KSL_BUNDLES_DIR` env var or `bundles.dir` config value overrides both with a
     * single directory (the operator escape hatch). Directories are created if absent.
     */
    fun bundleDirs(): List<Path> {
        System.getenv("KSL_BUNDLES_DIR")?.let { return listOf(ensureDir(expandHome(it))) }
        bundles.dir?.let { return listOf(ensureDir(expandHome(it))) }
        val workspace = UserSettingsStore().activeWorkspace()
        return listOf(
            WorkspaceLayout.bundlesDir(workspace.resolve(SERVER_APP_FOLDER), createIfMissing = true),
            WorkspaceLayout.bundlesDir(workspace, createIfMissing = true),
        )
    }

    /** The primary bundle directory — the server's own app folder; first of [bundleDirs]. */
    fun bundlesDir(): Path = bundleDirs().first()

    private fun ensureDir(p: Path): Path {
        runCatching { Files.createDirectories(p) }
        return p
    }

    /**
     * The result-cache directory: `KSL_RESULT_CACHE_DIR` > `cache.dir` > `KSLWork/KSL_MCP_APPS/result-cache/`.
     * Kept beside the run artifacts in the KSLWork workspace (like the desktop apps) so `~/.ksl` stays
     * settings-only rather than growing a cache. Created if absent.
     */
    fun resultCacheDir(): Path =
        resolveDir(
            "KSL_RESULT_CACHE_DIR", cache.dir,
            UserSettingsStore().activeWorkspace().resolve(SERVER_APP_FOLDER).resolve(RESULT_CACHE_FOLDER),
        )

    /**
     * The root for run **work output** — each result's `output/` (captured Welch/
     * trace files and the KSL database) and rendered `artifacts/` — under the
     * server's KSLWork app folder (`KSLWork/KSL_MCP_APPS/runs/`), resolved through
     * the user's active workspace. This is the same KSLWork layout the desktop
     * apps write into; the `~/.ksl` tree stays settings-only (config), not caches or work output.
     * `KSL_OUTPUT_DIR` overrides it (the operator escape hatch). Created if absent.
     */
    fun outputRoot(): Path {
        System.getenv("KSL_OUTPUT_DIR")?.let { return ensureDir(expandHome(it)) }
        val workspace = UserSettingsStore().activeWorkspace()
        return ensureDir(workspace.resolve(SERVER_APP_FOLDER).resolve(RUNS_FOLDER))
    }

    /** The MCP listen port: `KSL_MCP_PORT` > `server.mcpPort`. */
    fun mcpPort(): Int = System.getenv("KSL_MCP_PORT")?.toIntOrNull() ?: server.mcpPort

    /** The REST listen port: `KSL_REST_PORT` > `server.restPort`. */
    fun restPort(): Int = System.getenv("KSL_REST_PORT")?.toIntOrNull() ?: server.restPort

    /** The HTTP bind interface: `KSL_BIND_HOST` > `server.bindHost` (default localhost). */
    fun bindHost(): String = System.getenv("KSL_BIND_HOST") ?: server.bindHost

    /** The job (whole-run) wall-clock deadline in seconds (0 = none): `KSL_RUN_TIMEOUT_SECONDS` > `server.runTimeoutSeconds`. */
    fun runTimeoutSeconds(): Int = System.getenv("KSL_RUN_TIMEOUT_SECONDS")?.toIntOrNull() ?: server.runTimeoutSeconds

    /** The job wall-clock deadline as a [Duration], or null when no limit is configured (0). */
    fun runDeadline(): Duration? = runTimeoutSeconds().takeIf { it > 0 }?.seconds

    /** The shared bearer token gating the HTTP servers (null when none configured): `KSL_AUTH_TOKEN` > `server.authToken`. */
    fun authToken(): String? = (System.getenv("KSL_AUTH_TOKEN") ?: server.authToken)?.takeIf { it.isNotBlank() }

    /** The bundle-directory poll interval. */
    fun pollInterval(): Duration = bundles.pollSeconds.seconds

    private fun resolveDir(env: String, fileValue: String?, default: Path): Path {
        val chosen = System.getenv(env)?.let { expandHome(it) } ?: fileValue?.let { expandHome(it) } ?: default
        runCatching { Files.createDirectories(chosen) }
        return chosen
    }

    companion object {
        /** The server's own application folder under the KSLWork workspace
         *  (`KSLWork/KSL_MCP_APPS/`); its `bundles/` subfolder is searched before the
         *  shared `KSLWork/bundles/`. Shared by the MCP and REST transports. */
        const val SERVER_APP_FOLDER: String = "KSL_MCP_APPS"

        /** Subfolder of the server app folder holding per-result run output + artifacts
         *  (`KSLWork/KSL_MCP_APPS/runs/<resultId>/`). */
        const val RUNS_FOLDER: String = "runs"

        /** Subfolder of the server app folder holding the memoization result-cache
         *  (`KSLWork/KSL_MCP_APPS/result-cache/`) — kept in the workspace so `~/.ksl` stays settings-only. */
        const val RESULT_CACHE_FOLDER: String = "result-cache"

        /** Loads the config from `KSL_CONFIG_FILE` (else `~/.ksl/config.toml`); defaults when absent or unreadable. */
        fun load(): ServerConfig {
            val path = System.getenv("KSL_CONFIG_FILE")?.let { expandHome(it) } ?: defaultConfigFile()
            if (!Files.isRegularFile(path)) return ServerConfig()
            return runCatching { ServerConfigToml.decode(Files.readString(path)) }.getOrDefault(ServerConfig())
        }

        /** `~/.ksl/config.toml` — the conventional config location. */
        fun defaultConfigFile(): Path = kslHome().resolve("config.toml")

        /** `~/.ksl/bundles` — the default watched bundle directory. */
        fun defaultBundlesDir(): Path = kslHome().resolve("bundles")

        private fun kslHome(): Path = Path.of(System.getProperty("user.home")).resolve(".ksl")

        /** Expands a leading `~` to the user home; otherwise a literal path. */
        internal fun expandHome(p: String): Path =
            if (p == "~" || p.startsWith("~/")) {
                Path.of(System.getProperty("user.home")).resolve(p.removePrefix("~").trimStart('/'))
            } else {
                Path.of(p)
            }
    }
}

/** Bundle discovery and the dynamic-catalog watcher (Phase 8.6). */
@Serializable
data class BundlesConfig(
    @TomlComment("Watched bundle directory. Absent => ~/.ksl/bundles. KSL_BUNDLES_DIR overrides.")
    val dir: String? = null,
    @TomlComment("Seconds between directory scans for dropped/replaced/removed bundle JARs.")
    val pollSeconds: Long = 5,
)

/** The ResultStore's location and retention caps (Phase 8.4). */
@Serializable
data class CacheConfig(
    @TomlComment("Result-cache directory. Absent => KSLWork/KSL_MCP_APPS/result-cache. KSL_RESULT_CACHE_DIR overrides.")
    val dir: String? = null,
    @TomlComment("In-memory budget in bytes for retained result payloads (weight-based; default 64 MiB).")
    val maxMemoryBytes: Long = ResultStore.DEFAULT_MAX_MEMORY_BYTES,
    @TomlComment("Max results retained on disk; oldest are evicted past this. 0 = unbounded.")
    val maxDiskEntries: Int = 500,
    @TomlComment("On-disk byte budget for the result cache; oldest evicted past this. 0 = unbounded (default 128 MiB).")
    val maxDiskBytes: Long = ResultStore.DEFAULT_MAX_DISK_BYTES,
)

/** Transport and execution settings. */
@Serializable
data class ServerSettings(
    @TomlComment(
        "Network interface the HTTP servers (REST, MCP HTTP) bind to. Default\n" +
        "127.0.0.1 (localhost only) — the local-trust model. Use 0.0.0.0 to\n" +
        "accept connections from other machines; when you do, gate them with\n" +
        "[authToken] below and/or an SSH tunnel / firewall (the servers have no\n" +
        "TLS). KSL_BIND_HOST overrides."
    )
    val bindHost: String = "127.0.0.1",
    @TomlComment("MCP HTTP listen port. KSL_MCP_PORT overrides.")
    val mcpPort: Int = 3001,
    @TomlComment("REST listen port. KSL_REST_PORT overrides.")
    val restPort: Int = 8080,
    @TomlComment("Max concurrent runs/jobs before submissions are rejected at capacity.")
    val maxConcurrentJobs: Int = Runtime.getRuntime().availableProcessors(),
    @TomlComment(
        "Wall-clock deadline, in seconds, on a whole job (a run / experiment).\n" +
        "When a job outlives it the job is cancelled (a per-replication cap is the\n" +
        "enforcement backstop). 0 = no limit. KSL_RUN_TIMEOUT_SECONDS overrides."
    )
    val runTimeoutSeconds: Int = 0,
    @TomlComment(
        "Optional shared bearer token. When set (non-blank), the HTTP servers\n" +
        "(REST, MCP HTTP) require `Authorization: Bearer <token>` on every request\n" +
        "except the /health, /ready, /version probes; a missing/wrong token gets\n" +
        "401. Absent/blank = no auth (the local-trust default). Prefer the\n" +
        "KSL_AUTH_TOKEN env var over storing the secret in this file.\n" +
        "KSL_AUTH_TOKEN overrides."
    )
    val authToken: String? = null,
)
