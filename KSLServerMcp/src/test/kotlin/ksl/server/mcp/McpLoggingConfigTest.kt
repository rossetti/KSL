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

package ksl.server.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the MCP stdio invariant (Phase 9 A1): the server's logging config must
 * never write to stdout, because the stdio transport *is* the stdout channel —
 * a single log line there corrupts the protocol and disconnects the agent.
 *
 * This checks the config the launcher selects (`logback-ksl-mcp.xml`, via
 * `-Dlogback.configurationFile=…` in the build): it must route to `System.err`
 * and contain no `System.out`, and every console appender must explicitly target
 * stderr (a `ConsoleAppender` with no `<target>` defaults to stdout — exactly the
 * trap KSLCore's `logback.xml` falls into).
 */
class McpLoggingConfigTest {

    private val config: String =
        javaClass.classLoader.getResource("logback-ksl-mcp.xml")?.readText()
            ?: error("logback-ksl-mcp.xml is not on the classpath")

    @Test
    fun `the mcp logging config exists and routes to stderr`() {
        assertNotNull(config)
        assertTrue("System.err" in config, "MCP logging must target System.err")
    }

    @Test
    fun `the mcp logging config never targets stdout`() {
        assertFalse("System.out" in config, "MCP logging must never write to stdout (the protocol channel)")
        // Every ConsoleAppender must carry an explicit System.err target; one with
        // no <target> would silently default to stdout.
        val consoleAppenders = Regex("ConsoleAppender").findAll(config).count()
        val stderrTargets = Regex("""<target>\s*System\.err\s*</target>""").findAll(config).count()
        assertEquals(
            consoleAppenders,
            stderrTargets,
            "every ConsoleAppender must explicitly target System.err (no stdout default)",
        )
    }
}
