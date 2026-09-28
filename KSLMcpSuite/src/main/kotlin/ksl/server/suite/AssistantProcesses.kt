/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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
package ksl.server.suite

/**
 *  Whether a coding assistant is **running**, which is the fact the console could not report.
 *
 *  `AgentConfigurator.ClientState` says whether the MCP entry has been *written*. That is not the
 *  question a student is stuck on. Writing the entry takes effect only when the assistant restarts and
 *  re-reads its configuration, and on Windows closing an assistant's window does not end its process —
 *  so a student "restarts" it, the configuration is still the one loaded before Connect, the KSL tools
 *  do not appear, and the console has nothing to say about why.
 *
 *  Reporting *entry written, assistant still running* turns that silence into an explanation. It is
 *  deliberately only a report: see the plan's Phase 6 for why the action half waits on facts that can
 *  only be established on Windows.
 *
 *  Matching is on the process command line, which is all `ProcessHandle` offers portably. That is a
 *  heuristic and it is allowed to be wrong in the safe direction: a false negative says nothing and
 *  costs the student the explanation they did not have before, while a false positive would tell them
 *  to restart something already restarted. The markers are therefore specific rather than generous,
 *  and a case that cannot be decided is reported as unknown rather than guessed.
 */
internal object AssistantProcesses {

    /**
     *  Command-line fragments that identify each assistant's own process, keyed by the agent name
     *  `AgentConfigurator` reports so the two can be joined.
     *
     *  Windows is the case this exists for, and the case that gives the JVM the least to work with: the
     *  JDK implements no `commandLine()` there at all, so matching falls back to the executable path —
     *  see `visibleCommandLines`, which measures and explains it. An MSIX install does present a full
     *  command line to WMI, but WMI is not the API this uses, and only what the JVM can see counts here.
     *  Where a platform offers neither, an empty match is reported as unknown, never as "not running".
     */
    private val markers: Map<String, List<String>> = mapOf(
        "Claude Desktop" to listOf("Claude.app/Contents/MacOS/Claude", "claude.exe", "Claude Desktop"),
        "Cursor" to listOf("Cursor.app/Contents/MacOS/Cursor", "cursor.exe"),
        "Windsurf" to listOf("Windsurf.app/Contents/MacOS/Windsurf", "windsurf.exe"),
        "Codex" to listOf("codex"),
    )

    /**
     *  Fragments that mean a marker match is *not* the assistant itself.
     *
     *  On Windows, Claude Code's CLI is also named `claude.exe` — it lives under
     *  `AppData\Roaming\Claude\claude-code\<version>\` — and it runs as a *child* of Claude Desktop
     *  with no `--type=` flag to set it apart from the main process. So the `claude.exe` marker alone
     *  also matches a machine where Claude Desktop is closed and only a Claude Code session is open,
     *  and reports the assistant as running: exactly the false positive this matching is meant not to
     *  produce, telling a student to restart something that is not there. Only the path separates them.
     *
     *  Subtracting rather than narrowing the markers keeps a classic (non-MSIX) Claude Desktop install
     *  matching, whose executable sits under neither `WindowsApps` nor `claude-code`.
     */
    private val exclusions: Map<String, List<String>> = mapOf(
        "Claude Desktop" to listOf("claude-code"),
    )

    /** What is known about one assistant's process state. */
    internal enum class Running { YES, NO, UNKNOWN }

    /**
     *  Whether each named agent appears to be running.
     *
     *  @param agents the agent names to test, as `AgentConfigurator.state` reports them
     *  @return a verdict per agent; [Running.UNKNOWN] when this platform gives nothing to match on
     */
    fun runningByAgent(agents: List<String>): Map<String, Running> =
        verdictsFrom(agents, visibleCommandLines())

    /**
     *  One entry per process, holding whatever this platform lets the JVM see of it.
     *
     *  `commandLine()` is the better signal — it carries the arguments — and macOS supplies it. **Windows
     *  supplies none of it:** the JDK does not implement command-line retrieval there. Measured
     *  2026-09-27 on Windows 11, of **321** visible processes `commandLine()` was non-blank for **0**,
     *  while `command()` — the executable path — was populated for 130 and named Claude in 14 of them.
     *  Matching on `commandLine()` alone therefore made every verdict unknown on Windows, so the
     *  console's sharpened note could never appear on the one platform it exists for.
     *
     *  The executable path is enough for what the markers and the exclusions need, because the thing that
     *  distinguishes Claude Desktop from Claude Code on Windows **is** the path — both binaries are named
     *  `claude.exe`. So: the command line where a platform gives one, the executable path otherwise.
     *
     *  Empty when a platform gives neither, which `verdictsFrom` reports as unknown rather than guessing.
     */
    internal fun visibleCommandLines(): List<String> =
        runCatching {
            ProcessHandle.allProcesses()
                .map { handle ->
                    val info = handle.info()
                    info.commandLine().orElse("").ifBlank { info.command().orElse("") }
                }
                .filter { it.isNotBlank() }
                .toList()
        }.getOrDefault(emptyList())

    /**
     *  The matching itself, over a supplied command-line list rather than the live process table.
     *
     *  Split out so both the match and the exclusion are unit-testable without depending on what
     *  happens to be running on the machine — the same reason `AdminConsole` keeps its render and its
     *  gate as pure functions.
     *
     *  @param agents the agent names to test, as `AgentConfigurator.state` reports them
     *  @param commands one entry per visible process command line; null or empty when the platform
     *  gave nothing to match on
     *  @return a verdict per agent
     */
    internal fun verdictsFrom(agents: List<String>, commands: List<String>?): Map<String, Running> {
        // No command lines at all means the JVM cannot see them here (a restricted platform, a
        // container). Saying "not running" then would be a confident wrong answer.
        if (commands.isNullOrEmpty()) return agents.associateWith { Running.UNKNOWN }
        return agents.associateWith { agent ->
            val fragments = markers[agent] ?: return@associateWith Running.UNKNOWN
            val excluded = exclusions[agent].orEmpty()
            // Per line, not per agent: one process being Claude Code must not stop another line that
            // really is the assistant from counting.
            val matched = commands.any { line ->
                fragments.any { line.contains(it, ignoreCase = true) } &&
                    excluded.none { line.contains(it, ignoreCase = true) }
            }
            if (matched) Running.YES else Running.NO
        }
    }
}
