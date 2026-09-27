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
     *  Windows is the case this exists for and the case least verifiable from a developer's Mac: an
     *  MSIX-packaged install may not present a matchable command line at all, which is why an empty
     *  match is reported as unknown rather than as "not running".
     */
    private val markers: Map<String, List<String>> = mapOf(
        "Claude Desktop" to listOf("Claude.app/Contents/MacOS/Claude", "claude.exe", "Claude Desktop"),
        "Cursor" to listOf("Cursor.app/Contents/MacOS/Cursor", "cursor.exe"),
        "Windsurf" to listOf("Windsurf.app/Contents/MacOS/Windsurf", "windsurf.exe"),
        "Codex" to listOf("codex"),
    )

    /** What is known about one assistant's process state. */
    internal enum class Running { YES, NO, UNKNOWN }

    /**
     *  Whether each named agent appears to be running.
     *
     *  @param agents the agent names to test, as `AgentConfigurator.state` reports them
     *  @return a verdict per agent; [Running.UNKNOWN] when this platform gives nothing to match on
     */
    fun runningByAgent(agents: List<String>): Map<String, Running> {
        val commands = runCatching {
            ProcessHandle.allProcesses()
                .map { it.info().commandLine().orElse("") }
                .filter { it.isNotBlank() }
                .toList()
        }.getOrNull()
        // No command lines at all means the JVM cannot see them here (a restricted platform, a
        // container). Saying "not running" then would be a confident wrong answer.
        if (commands.isNullOrEmpty()) return agents.associateWith { Running.UNKNOWN }
        return agents.associateWith { agent ->
            val fragments = markers[agent] ?: return@associateWith Running.UNKNOWN
            if (commands.any { line -> fragments.any { line.contains(it, ignoreCase = true) } }) {
                Running.YES
            } else {
                Running.NO
            }
        }
    }
}
