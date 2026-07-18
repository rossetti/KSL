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

/**
 * Readiness of one capability, contributed to `/ready` and `/status`. `detail` is a short
 * human-readable phrase for the console (e.g. "8 bundles loaded", "index: 1,240 chunks").
 */
data class CapabilityReadiness(val id: String, val ready: Boolean, val detail: String)

/**
 * One MCP tool surface (simulation, textbook, or source-code) that can be registered onto the
 * aggregated suite `Server`. This is the minimal contract that lets the heavy simulation domain
 * (backed by KSLServiceCore) and the light Lucene search domains (backed by the KSLBookSearch /
 * KSLCodeSearch libraries) coexist on one server without forcing either into the other's shape:
 *
 * - the capability owns its heavy state (the bundle registry + run services, or a Lucene index),
 *   built ONCE in the composition root and captured by the instance — never rebuilt per session;
 * - [registerTools] adds this surface's tools to a (possibly shared) MCP server;
 * - [instructions] contributes connect-time orientation, merged into the server's instructions so a
 *   server running only a subset of capabilities advertises only those surfaces;
 * - [readiness] contributes a snapshot to `/ready` and `/status`.
 */
interface McpToolCapability {
    /** Stable short id: "sim", "book", or "code". */
    val id: String

    /** Connect-time routing guidance for this surface, or null to contribute none. */
    val instructions: String?

    /** Register this surface's tools onto [server]. Tool names are disjoint across capabilities. */
    fun registerTools(server: Server)

    /** A readiness snapshot for `/ready` and `/status`. */
    fun readiness(): CapabilityReadiness
}
