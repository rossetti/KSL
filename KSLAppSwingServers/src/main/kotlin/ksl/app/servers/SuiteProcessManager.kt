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

package ksl.app.servers

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.stream.Collectors

/**
 * The headless core of the KSL Server Manager: discover, health-check, start, stop, and clean up the
 * KSL MCP suite and its related JVMs. Pure JDK — `ProcessHandle` + `java.net.http`, no Swing and no
 * KSLCore — so it is unit-testable and the Swing GUI is a thin layer over it.
 */
object SuiteProcessManager {

    /** What a discovered KSL JVM is, inferred from its command line. */
    enum class Kind { SUITE, BRIDGE, MODEL, CODE, BOOK }

    data class KslProcess(
        val pid: Long,
        val kind: Kind,
        val command: String,
        val parentPid: Long?,
    ) {
        /** Orphaned == reparented to init (pid 1): its launching client is gone (a leaked process). */
        val isOrphan: Boolean get() = parentPid == 1L
    }

    enum class Health { UP, DOWN }

    const val DEFAULT_HEALTH_URL: String = "http://127.0.0.1:3001/health"

    // Order matters: match the more specific suffixes before the bare "ksl-mcp" (a substring of the
    // suite/code/book names). The classpath-launched thin model server shows its main class instead.
    private val markers: List<Pair<String, Kind>> = listOf(
        "ksl-suite-mcp" to Kind.SUITE,
        "ksl-bridge" to Kind.BRIDGE,
        "ksl-code-mcp" to Kind.CODE,
        "ksl-book-mcp" to Kind.BOOK,
        "server.mcp.LauncherKt" to Kind.MODEL,
        "ksl-mcp" to Kind.MODEL,
    )

    private fun classify(command: String): Kind? = markers.firstOrNull { command.contains(it.first) }?.second

    /** All live JVMs that look like a KSL MCP suite / bridge / server, classified by kind. */
    fun findKslProcesses(): List<KslProcess> {
        val handles = ProcessHandle.allProcesses().collect(Collectors.toList())
        return handles.mapNotNull { ph ->
            val cmd = ph.info().commandLine().orElse("")
            if (cmd.isBlank()) return@mapNotNull null
            val kind = classify(cmd) ?: return@mapNotNull null
            KslProcess(ph.pid(), kind, cmd, ph.parent().map { it.pid() }.orElse(null))
        }
    }

    /** Orphaned (client-less) KSL JVMs — the leaked processes the WS1 fix prevents going forward. */
    fun findOrphans(): List<KslProcess> = findKslProcesses().filter { it.isOrphan }

    /** HTTP health of a running suite (GET /health). DOWN on any connection or timeout error. */
    fun health(url: String = DEFAULT_HEALTH_URL, timeout: Duration = Duration.ofSeconds(2)): Health =
        try {
            val client = HttpClient.newBuilder().connectTimeout(timeout).build()
            val req = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() in 200..299 && resp.body().contains("\"status\"")) Health.UP else Health.DOWN
        } catch (e: Exception) {
            Health.DOWN
        }

    fun isSuiteRunning(url: String = DEFAULT_HEALTH_URL): Boolean = health(url) == Health.UP

    /**
     * Graceful-then-forceful termination: `destroy()` (SIGTERM) every pid, wait up to [graceMillis],
     * then `destroyForcibly()` any survivors. Returns the pids that are no longer alive afterward.
     */
    fun terminate(pids: List<Long>, graceMillis: Long = 3000): List<Long> {
        val handles = pids.mapNotNull { ProcessHandle.of(it).orElse(null) }
        handles.forEach { it.destroy() }
        val deadline = System.nanoTime() + graceMillis * 1_000_000
        while (System.nanoTime() < deadline && handles.any { it.isAlive }) Thread.sleep(100)
        handles.filter { it.isAlive }.forEach { it.destroyForcibly() }
        return pids.filter { ProcessHandle.of(it).map { h -> !h.isAlive }.orElse(true) }
    }

    /** Start the aggregator jar as a detached background process (output discarded). */
    fun startSuite(suiteJar: Path, port: Int? = null, kslWork: Path? = null): Process {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val pb = ProcessBuilder(java, "-jar", suiteJar.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        port?.let { pb.environment()["KSL_MCP_PORT"] = it.toString() }
        kslWork?.let { pb.environment()["KSLWORK"] = it.toString() }
        return pb.start()
    }
}

/** Dev CLI: print the KSL MCP process inventory and the suite's health. */
fun main() {
    val procs = SuiteProcessManager.findKslProcesses()
    println("KSL MCP processes (${procs.size}):")
    procs.sortedBy { it.kind }.forEach { p ->
        println("  pid=${p.pid}  ${p.kind}${if (p.isOrphan) "  [ORPHAN]" else ""}")
    }
    println("suite health @ ${SuiteProcessManager.DEFAULT_HEALTH_URL}: ${SuiteProcessManager.health()}")
}
