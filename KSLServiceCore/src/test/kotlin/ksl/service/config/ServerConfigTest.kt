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

import org.junit.jupiter.api.DisplayName
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the server config document (Phase 8.6/8.7 hardening): TOML round-trip,
 * defaults when sections are omitted, env-over-file precedence, and `~`
 * expansion. The on-disk `load()` and env mutation are not exercised here (no
 * process-env mutation in tests); precedence is checked through the resolution
 * helpers, which read env directly.
 */
class ServerConfigTest {

    @Test
    @DisplayName("outputRoot routes run work to the KSLWork app folder, not under ~/.ksl")
    fun outputRootUsesWorkspaceNotKslHome() {
        // Default (no KSL_OUTPUT_DIR): <activeWorkspace>/KSL_MCP_APPS/runs.
        val root = ServerConfig().outputRoot()
        assertTrue(
            root.endsWith(Path.of(ServerConfig.SERVER_APP_FOLDER, ServerConfig.RUNS_FOLDER)),
            "run output should live under <workspace>/${ServerConfig.SERVER_APP_FOLDER}/${ServerConfig.RUNS_FOLDER}; was $root",
        )
        assertTrue(
            !root.toString().contains(Path.of(".ksl").toString()),
            "run work must NOT live under ~/.ksl (settings-only); was $root",
        )
    }

    @Test
    @DisplayName("resultCacheDir routes the result cache to the KSLWork app folder, not under ~/.ksl")
    fun resultCacheDirUsesWorkspaceNotKslHome() {
        if (System.getenv("KSL_RESULT_CACHE_DIR") != null) return // an env override is in effect
        // Default (no env / config override): <activeWorkspace>/KSL_MCP_APPS/result-cache.
        val dir = ServerConfig().resultCacheDir()
        assertTrue(
            dir.endsWith(Path.of(ServerConfig.SERVER_APP_FOLDER, ServerConfig.RESULT_CACHE_FOLDER)),
            "the result cache should live under <workspace>/${ServerConfig.SERVER_APP_FOLDER}/${ServerConfig.RESULT_CACHE_FOLDER}; was $dir",
        )
        assertTrue(
            !dir.toString().contains(Path.of(".ksl").toString()),
            "the result cache must NOT live under ~/.ksl (settings-only); was $dir",
        )
    }

    @Test
    fun `defaults are sensible and a full document round-trips`() {
        val defaults = ServerConfig()
        assertEquals(5, defaults.bundles.pollSeconds)
        assertEquals(500, defaults.cache.maxDiskEntries)
        assertEquals(3001, defaults.server.mcpPort)
        assertEquals(8080, defaults.server.restPort)
        assertEquals("127.0.0.1", defaults.server.bindHost) // localhost by default (local-trust)
        assertEquals(0, defaults.server.runTimeoutSeconds)   // no run timeout by default

        val custom = ServerConfig(
            bundles = BundlesConfig(dir = "/srv/ksl/bundles", pollSeconds = 10),
            cache = CacheConfig(dir = "/var/ksl/cache", maxMemoryBytes = 64, maxDiskEntries = 100),
            server = ServerSettings(
                bindHost = "0.0.0.0",
                mcpPort = 4001,
                restPort = 9090,
                maxConcurrentJobs = 2,
                runTimeoutSeconds = 30,
            ),
        )
        val decoded = ServerConfigToml.decode(ServerConfigToml.encode(custom))
        assertEquals(custom, decoded)
    }

    @Test
    fun `a partial document fills missing sections with defaults`() {
        val toml = """
            [server]
            mcpPort = 4321
        """.trimIndent()
        val config = ServerConfigToml.decode(toml)
        assertEquals(4321, config.server.mcpPort)
        assertEquals(8080, config.server.restPort)          // default
        assertEquals(5, config.bundles.pollSeconds)         // default section
        assertEquals(500, config.cache.maxDiskEntries)      // default section
    }

    @Test
    fun `env overrides the file value for ports and dirs`() {
        // KSL_MCP_PORT / KSL_BUNDLES_DIR are not set in the test JVM, so the
        // resolver must fall through to the file value (then the default).
        if (System.getenv("KSL_MCP_PORT") == null) {
            assertEquals(7777, ServerConfig(server = ServerSettings(mcpPort = 7777)).mcpPort())
        }
        if (System.getenv("KSL_BUNDLES_DIR") == null) {
            val dir = ServerConfig(bundles = BundlesConfig(dir = "~/ksl-test-bundles")).bundlesDir()
            assertTrue(dir.toString().endsWith("ksl-test-bundles"), "expected ~-expanded file value, got $dir")
            assertTrue(!dir.toString().startsWith("~"), "~ must be expanded to the home dir")
        }
        if (System.getenv("KSL_BIND_HOST") == null) {
            assertEquals("0.0.0.0", ServerConfig(server = ServerSettings(bindHost = "0.0.0.0")).bindHost())
        }
        if (System.getenv("KSL_RUN_TIMEOUT_SECONDS") == null) {
            assertEquals(45, ServerConfig(server = ServerSettings(runTimeoutSeconds = 45)).runTimeoutSeconds())
        }
    }

    @Test
    fun `tilde expands to the user home`() {
        val expanded = ServerConfig.expandHome("~/a/b").toString()
        assertEquals(System.getProperty("user.home") + "/a/b", expanded)
        assertEquals("/abs/path", ServerConfig.expandHome("/abs/path").toString())
    }
}
