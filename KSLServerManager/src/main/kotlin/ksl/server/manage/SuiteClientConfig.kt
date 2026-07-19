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

package ksl.server.manage

import ksl.agent.config.AgentConfigurator
import ksl.agent.config.LaunchSpec

/**
 * Local client-config for the suite: write / remove / query the ONE `ksl-suite` MCP entry (which
 * launches the bridge at the suite URL) in each detected coding agent, over the shared KSLAgentConfig
 * library. This is a machine-local operation — meaningful only where the coding agents live.
 */
object SuiteClientConfig {

    const val SUITE_KEY: String = "ksl-suite"

    fun configure(bridgeCommand: String, suiteUrl: String): List<AgentConfigurator.ConfigResult> =
        AgentConfigurator.configure(SUITE_KEY, LaunchSpec(bridgeCommand, listOf("--url", suiteUrl)))

    fun remove(): List<AgentConfigurator.ConfigResult> = AgentConfigurator.remove(SUITE_KEY)

    fun state(): List<AgentConfigurator.ClientState> = AgentConfigurator.state(SUITE_KEY)
}
