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

/**
 * The suite's own distribution version, read from THIS module's jar manifest `Implementation-Version`
 * (stamped from `gradle.properties` `kslSuiteVersion`), or `"dev"` from compiled classes (tests / `run`).
 *
 * Distinct from `ksl.service.config.BuildInfo`, which reads KSLServiceCore's manifest — the server-stack
 * code version (`kslServerVersion`). In the thin distribution the suite jar and KSLServiceCore jar are
 * separate, so `BuildInfo` would report the engine version (e.g. 1.0.0), not the release a student
 * installed (`ksl-suite.zip`, e.g. 0.1.0). The suite advertises THIS version in `/version`, `/health`,
 * `/status`, the console header, and its MCP `serverInfo`, so what it reports matches the release tag.
 */
object SuiteBuildInfo {
    val version: String = SuiteBuildInfo::class.java.`package`?.implementationVersion ?: "dev"
}
