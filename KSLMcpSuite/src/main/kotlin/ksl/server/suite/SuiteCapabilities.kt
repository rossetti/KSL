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

package ksl.server.suite

import io.modelcontextprotocol.kotlin.sdk.server.Server
import ksl.book.search.BookSearch
import ksl.book.search.BookStore
import ksl.code.search.CodeSearch
import ksl.code.search.CodeStore
import ksl.server.mcp.KslMcpServer
import ksl.server.mcp.KslMcpTools
import ksl.server.suite.book.BookToolRegistry
import ksl.server.suite.code.CodeToolRegistry
import ksl.service.capability.run.BundleRegistry
import ksl.service.usage.ToolCallSession
import ksl.service.usage.ToolUsageRecorder

/**
 * The simulation capability — run / experiment / optimize / fit, plus guided prompts — backed by the
 * KSLServiceCore run services via `KslMcpTools`. Its heavy state (the bundle registry + services) is
 * built once in the composition root and injected here.
 */
class SimMcpCapability(
    private val tools: KslMcpTools,
    private val registry: BundleRegistry,
    private val recorder: ToolUsageRecorder = ToolUsageRecorder.NONE,
) : McpToolCapability {
    override val id: String = "sim"
    override val instructions: String = SIM_INSTRUCTIONS
    override fun registerTools(server: Server, session: ToolCallSession) {
        KslMcpServer.registerKslTools(server, tools, recorder, session)
    }

    override fun readiness(): CapabilityReadiness {
        val n = registry.listBundles().size
        return CapabilityReadiness(id, ready = true, detail = "$n model bundle${if (n == 1) "" else "s"} loaded")
    }
}

/** The textbook search capability, backed by the KSLBookSearch library. */
class BookMcpCapability(
    private val store: BookStore,
    private val search: BookSearch,
    private val recorder: ToolUsageRecorder = ToolUsageRecorder.NONE,
) : McpToolCapability {
    override val id: String = "book"
    override val instructions: String = BOOK_INSTRUCTIONS
    override fun registerTools(server: Server, session: ToolCallSession) {
        BookToolRegistry.registerBookTools(server, store, search, recorder, session)
    }

    override fun readiness(): CapabilityReadiness {
        val n = store.chunks.size
        return CapabilityReadiness(id, ready = n > 0, detail = if (n > 0) "index: $n sections" else "no content (book not rendered)")
    }
}

/** The source-code search capability, backed by the KSLCodeSearch library. */
class CodeMcpCapability(
    private val store: CodeStore,
    private val search: CodeSearch,
    private val recorder: ToolUsageRecorder = ToolUsageRecorder.NONE,
) : McpToolCapability {
    override val id: String = "code"
    override val instructions: String = CODE_INSTRUCTIONS
    override fun registerTools(server: Server, session: ToolCallSession) {
        CodeToolRegistry.registerCodeTools(server, store, search, recorder, session)
    }

    override fun readiness(): CapabilityReadiness {
        val n = store.meta.declarationCount
        return CapabilityReadiness(id, ready = n > 0, detail = "index: $n declarations (KSL ${store.meta.kslVersion})")
    }
}

// Per-surface connect-time routing guidance. The serving helper joins the enabled capabilities'
// instructions under a short suite preamble, so a server running only a subset advertises only those
// surfaces. (Split out of the former single SUITE_INSTRUCTIONS block.)

// Routing alone was not enough. Observed 2026-08-13: an agent followed the routing correctly and
// still fumbled one layer down — guessing input keys, hand-authoring a config, putting a
// distribution mean in numericControls, and approximating a comparison with repeated run_model.
// That guidance lived only in individual tool descriptions, which some clients load late or
// piecemeal, so it arrived after the mistake. This field is always in context, so it is the one
// channel that reaches every client before the first call. Keep it terse for the same reason.
internal const val SIM_INSTRUCTIONS =
    "RUN and analyze simulation models — single runs, scenario comparisons, experiments, " +
        "optimization, and distribution fitting. Unsure what exists or what to do? call get_started; " +
        "call describe_model before setting inputs — don't guess keys. Author a run or scenario config " +
        "with run_template -> edit -> validate_run_config -> run_config; don't hand-write it. A " +
        "distribution parameter (a mean, a rate) is an rvOverride, not a control. To compare alternatives " +
        "and pick the best, run every scenario in ONE run_config batch (common random numbers), then " +
        "db_compare / db_compare_report — don't approximate a comparison with repeated run_model. Results " +
        "return reports and plots as artifacts (get_artifact); hand the user the link."

internal const val CODE_INSTRUCTIONS =
    "SEARCH the KSL SOURCE CODE and API — for ANY question about a KSL class, function, or API, call " +
        "search_code FIRST, then get_class and get_example, and cite the returned source URLs. Do not " +
        "invent KSL names or signatures; verify them here."

internal const val BOOK_INSTRUCTIONS =
    "SEARCH the KSL TEXTBOOK — for ANY simulation concept, method, or homework question, call " +
        "search_textbook FIRST (before general knowledge or web search), then get_section and cite the " +
        "section URLs."
