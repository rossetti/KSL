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

package ksl.app.single.results

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 *  Substrate tests for [SingleAppPaths] — pure path-resolution
 *  primitives for the single-document app layout.  No filesystem
 *  touch required.
 *
 *  Backfilled in Phase E.4 — single-app substrate gap closure.
 */
class SingleAppPathsTest {

    private val workspace: Path = Path.of("/home/user/ksl-workspace")

    // ── analysisFolder ───────────────────────────────────────────────────

    @Test
    fun `analysisFolder preserves already-safe characters including dots`() {
        // Dots are intentionally preserved (vs. sanitizeAnalysisName)
        // because Single-app users routinely embed version-like or
        // path-like tokens in their analysis names.
        assertEquals("baseline.v2", SingleAppPaths.analysisFolder("baseline.v2"))
        assertEquals("mm1.queue-study", SingleAppPaths.analysisFolder("mm1.queue-study"))
        assertEquals("a_b-c.d", SingleAppPaths.analysisFolder("a_b-c.d"))
    }

    @Test
    fun `analysisFolder replaces spaces and other unsafe characters with underscore`() {
        assertEquals("Queueing_Study", SingleAppPaths.analysisFolder("Queueing Study"))
        assertEquals("Run__1", SingleAppPaths.analysisFolder("Run #1"))
        assertEquals("path_segment", SingleAppPaths.analysisFolder("path/segment"))
    }

    @Test
    fun `analysisFolder is idempotent on already-safe input`() {
        val safe = "already-safe.v1_2"
        assertEquals(safe, SingleAppPaths.analysisFolder(safe))
        assertEquals(safe, SingleAppPaths.analysisFolder(SingleAppPaths.analysisFolder(safe)))
    }

    @Test
    fun `analysisFolder preserves empty input verbatim`() {
        // Unlike sanitizeAnalysisName, the single-app variant does
        // NOT fall back to "Untitled" — callers (appWorkspaceDir in
        // particular) decide what to do with empty analysis names.
        assertEquals("", SingleAppPaths.analysisFolder(""))
    }

    // ── appWorkspaceDir ──────────────────────────────────────────────────

    @Test
    fun `appWorkspaceDir nests by sanitized analysisName when set and non-Untitled`() {
        val ws = SingleAppPaths.appWorkspaceDir(
            activeWorkspace = workspace,
            analysisName = "Queueing Study",
            modelName = "ShouldBeIgnored"
        )
        assertEquals(workspace.resolve("Queueing_Study"), ws,
            "Analysis-name branch must win over modelName when both are present.")
    }

    @Test
    fun `appWorkspaceDir falls back to modelName when analysisName is blank`() {
        val ws = SingleAppPaths.appWorkspaceDir(
            activeWorkspace = workspace,
            analysisName = "",
            modelName = "MM1Model"
        )
        assertEquals(workspace.resolve("MM1Model"), ws)
    }

    @Test
    fun `appWorkspaceDir falls back to modelName when analysisName is the Untitled sentinel`() {
        val ws = SingleAppPaths.appWorkspaceDir(
            activeWorkspace = workspace,
            analysisName = SingleAppPaths.UNTITLED,
            modelName = "MM1Model"
        )
        assertEquals(workspace.resolve("MM1Model"), ws,
            "The Untitled sentinel must trigger the modelName fallback.")
    }

    @Test
    fun `appWorkspaceDir falls back to modelName when analysisName is whitespace-only`() {
        val ws = SingleAppPaths.appWorkspaceDir(
            activeWorkspace = workspace,
            analysisName = "   ",
            modelName = "MM1Model"
        )
        assertEquals(workspace.resolve("MM1Model"), ws,
            "A whitespace-only analysisName is treated as blank.")
    }

    @Test
    fun `appWorkspaceDir returns the parent workspace when both analysisName and modelName are empty`() {
        // Probe-failure case: no name to derive a folder from.  File
        // dialogs still get a valid starting point.
        val ws = SingleAppPaths.appWorkspaceDir(
            activeWorkspace = workspace,
            analysisName = "",
            modelName = ""
        )
        assertEquals(workspace, ws,
            "Empty analysisName + empty modelName must return the parent workspace verbatim.")
    }

    @Test
    fun `appWorkspaceDir uses modelName verbatim - caller is responsible for sanitization`() {
        // KSL's Model(simulationName) constructor pre-sanitizes
        // spaces to underscores, so models built through it satisfy
        // the contract.  This test documents the contract by
        // showing the modelName branch does not re-sanitize.
        val ws = SingleAppPaths.appWorkspaceDir(
            activeWorkspace = workspace,
            analysisName = "",
            modelName = "My_Sim_Name"
        )
        assertEquals(workspace.resolve("My_Sim_Name"), ws)
    }

    // ── reportsDir ───────────────────────────────────────────────────────

    @Test
    fun `reportsDir resolves the reports subdirectory under any appWorkspace`() {
        val appWs = workspace.resolve("analysis-1")
        val reports = SingleAppPaths.reportsDir(appWs)
        assertEquals(appWs.resolve("reports"), reports)
    }

    @Test
    fun `appWorkspaceDir then reportsDir compose end-to-end`() {
        // Canonical single-app layout:
        //   <activeWorkspace>/<analysisFolder>/reports/
        val appWs = SingleAppPaths.appWorkspaceDir(
            activeWorkspace = workspace,
            analysisName = "Queueing Study",
            modelName = "ShouldBeIgnored"
        )
        val reports = SingleAppPaths.reportsDir(appWs)
        assertEquals(workspace.resolve("Queueing_Study").resolve("reports"), reports)
    }
}
