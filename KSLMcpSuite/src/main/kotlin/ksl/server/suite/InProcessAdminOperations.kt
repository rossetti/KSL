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

import ksl.service.admin.CapabilityStatus
import ksl.service.admin.ServerAdminOperations
import ksl.service.admin.SuiteStatus
import ksl.service.usage.UsageEvent
import ksl.service.usage.UsageStore
import ksl.service.usage.UsageSummary

/**
 * The in-process implementation of [ServerAdminOperations] that backs the suite's built-in console
 * and `/status`. It reads live server state directly — each enabled capability's own readiness and
 * the shared [UsageStore] — so there is no HTTP hop back to itself. A remote UI / CLI uses the HTTP
 * implementation (in KSLServerManager) against `/admin`; both render the same DTOs.
 *
 * All known capability ids are reported so a disabled surface still shows up (enabled = false),
 * which is what the console's capability panel needs.
 */
class InProcessAdminOperations(
    private val version: String,
    private val enabledCapabilities: List<McpToolCapability>,
    private val usage: UsageStore,
    private val allCapabilityIds: List<String> = listOf("sim", "book", "code"),
) : ServerAdminOperations {

    override fun status(): SuiteStatus {
        val summary = usage.summary()
        val byId = enabledCapabilities.associateBy { it.id }
        val capabilities = allCapabilityIds.map { id ->
            val readiness = byId[id]?.readiness()
            CapabilityStatus(
                id = id,
                enabled = id in byId,
                ready = readiness?.ready ?: false,
                detail = readiness?.detail ?: "disabled",
                callCount = summary.byCapability[id] ?: 0,
            )
        }
        return SuiteStatus(
            version = version,
            capabilities = capabilities,
            served = summary.total,
            lastActivityMillis = summary.lastActivityMillis,
        )
    }

    override fun usageSummary(): UsageSummary = usage.summary()

    override fun recentActivity(limit: Int): List<UsageEvent> = usage.recent(limit)
}
