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

package ksl.service.admin

import kotlinx.serialization.Serializable
import ksl.service.usage.UsageEvent
import ksl.service.usage.UsageSummary

/** One tool surface's live status for `/status` and the console. */
@Serializable
data class CapabilityStatus(
    val id: String,
    val enabled: Boolean,
    val ready: Boolean,
    val detail: String,
    val callCount: Int,
)

/** The server's live status snapshot: identity, per-capability readiness, and processing totals. */
@Serializable
data class SuiteStatus(
    val version: String,
    val capabilities: List<CapabilityStatus>,
    val served: Int,
    val lastActivityMillis: Long? = null,
)

/**
 * The transport-agnostic management surface every presentation talks to. Both the suite's built-in
 * web console (via an in-process implementation reading live server state) and any external UI / CLI
 * (via an HTTP implementation calling `/admin`) render the SAME `@Serializable` DTOs, so they cannot
 * diverge. The read side (status, usage, recent activity) lands here; command operations (toggle a
 * capability, export usage, configure a local client, clean orphans) are added with the console and
 * the management layer.
 */
interface ServerAdminOperations {
    /** A live status snapshot: version, per-capability readiness, and served/last-activity totals. */
    fun status(): SuiteStatus

    /** Aggregate usage counts for the console summary and export. */
    fun usageSummary(): UsageSummary

    /** The most recent tool calls, newest first, for the live activity feed. */
    fun recentActivity(limit: Int = 50): List<UsageEvent>
}
