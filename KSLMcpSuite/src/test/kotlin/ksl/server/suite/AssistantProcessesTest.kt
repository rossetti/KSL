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

import ksl.agent.config.AgentConfigurator
import ksl.service.admin.SuiteStatus
import ksl.service.usage.UsageSummary
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  The console could say whether an assistant's MCP entry had been *written*, never whether the
 *  assistant was *running* — and the second is what decides whether it has read the first. These pin
 *  the reporting, which is all Phase 6a ships; the quit-and-relaunch action waits on facts that can
 *  only be established on Windows.
 */
class AssistantProcessesTest {

    @Test
    @DisplayName("an agent with no markers is unknown, never reported as not running")
    fun unmatchableAgentIsUnknown() {
        // A guessed "not running" would tell a student to start something already started. An agent this
        // code has no fragments for -- or a platform that hides command lines, which an MSIX-packaged
        // Windows install may -- has to say so rather than pick.
        val verdicts = AssistantProcesses.runningByAgent(listOf("Some Future Assistant"))
        assertEquals(AssistantProcesses.Running.UNKNOWN, verdicts["Some Future Assistant"])
    }

    @Test
    @DisplayName("every configured agent gets a verdict")
    fun everyAgentAnswered() {
        val agents = listOf("Claude Desktop", "Cursor", "Windsurf", "Codex")
        val verdicts = AssistantProcesses.runningByAgent(agents)
        assertEquals(agents.toSet(), verdicts.keys, "the console joins these by name, so none may be missing")
        assertTrue(verdicts.values.all { it in AssistantProcesses.Running.entries }, "no null verdicts")
    }

    @Test
    @DisplayName("a configured assistant that is running is told to quit completely, not just restart")
    fun runningAssistantGetsTheSharperInstruction() {
        // Rendered with a real ClientState for whichever assistant is actually running on this machine,
        // so the branch under test is the one a student sees. When none is running the weaker note is
        // correct and is what this asserts instead.
        val agents = listOf("Claude Desktop", "Cursor", "Windsurf", "Codex")
        val runningAgent = AssistantProcesses.runningByAgent(agents)
            .entries.firstOrNull { it.value == AssistantProcesses.Running.YES }?.key

        val clients = listOf(
            AgentConfigurator.ClientState(runningAgent ?: "Claude Desktop", present = true, path = "/x/config.json"),
        )
        val html = AdminConsole.renderConsole(
            SuiteStatus(version = "test", capabilities = emptyList(), served = 0, lastActivityMillis = null),
            UsageSummary(total = 0, ok = 0, byTool = emptyMap(), byCapability = emptyMap()),
            emptyList(),
            clients,
            loopback = true,
        )

        if (runningAgent != null) {
            assertTrue(
                "still running" in html && "restart your assistant" in html,
                "a running assistant must be told the restart it thinks it did was not one"
            )
            assertTrue(
                "closing the window is not enough" in html,
                "and why: on Windows the process outlives the window"
            )
            assertTrue("Task Manager" in html, "and where to look on Windows, which is the failing case")
        } else {
            assertTrue(
                "restart your assistant" in html,
                "with nothing running, the standing reminder is the right note"
            )
        }
    }
}
