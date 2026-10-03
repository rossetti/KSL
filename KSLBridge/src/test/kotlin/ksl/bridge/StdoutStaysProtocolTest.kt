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

package ksl.bridge

import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bridge's stdout is the MCP channel, so nothing but protocol may ever reach it. Suite 0.4.0 shipped a
 * bridge whose first stdout line was kotlin-logging 8's startup banner (the MCP SDK brought kotlin-logging 8
 * in), which a stdio client reads as a malformed message. This runs the bridge as `java -jar` would, with
 * none of the launcher's JVM flags, so it holds even when the bridge is started without its launcher.
 */
class StdoutStaysProtocolTest {

    @Test
    fun nothingButProtocolReachesStdout() {
        // A port nobody listens on, so the bridge starts, fails to reach a suite and exits.
        val port = ServerSocket(0).use { it.localPort }
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"), "ksl.bridge.MainKt",
            "--url", "http://127.0.0.1:$port/"
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.close() // the client hangs up at once
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val stdout = process.inputStream.bufferedReader().readText()
        assertFalse("kotlin-logging" in stdout, "a logging banner reached the MCP channel:\n$stdout")
        assertTrue(stdout.isBlank() || stdout.trimStart().startsWith("{"), "stdout is not protocol:\n$stdout")
    }
}
