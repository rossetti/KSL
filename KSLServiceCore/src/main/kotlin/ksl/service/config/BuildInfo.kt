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

package ksl.service.config

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The build version a server reports about itself — read from the jar manifest's
 * `Implementation-Version` (stamped from `gradle.properties` `kslServerVersion`,
 * A7), or `"dev"` when running from compiled classes rather than a packaged jar
 * (e.g. in tests or `./gradlew run`).
 */
object BuildInfo {
    val version: String = BuildInfo::class.java.`package`?.implementationVersion ?: "dev"
}

/**
 * Shared bodies for the `/health` (liveness) and `/ready` (readiness) endpoints,
 * so the REST and MCP-HTTP servers report the same shape (Phase 9 A4).
 *
 * - **Liveness** answers "is the process up and responsive at all?" — always
 *   `UP` once the routes are serving.
 * - **Readiness** answers "is it ready to do useful work?" — for us, that the
 *   initial bundle scan has completed. A server can be live but not yet ready.
 */
object HealthEndpoints {

    /** `{"status":"UP","service":<name>,"version":<ver>}` */
    fun healthJson(service: String): String =
        buildJsonObject {
            put("status", "UP")
            put("service", service)
            put("version", BuildInfo.version)
        }.toString()

    /** `{"status":"READY"|"STARTING","ready":<bool>}` */
    fun readyJson(ready: Boolean): String =
        buildJsonObject {
            put("status", if (ready) "READY" else "STARTING")
            put("ready", ready)
        }.toString()

    /** `{"service":<name>,"version":<ver>}` — the body of `GET /version` (A7). */
    fun versionJson(service: String): String =
        buildJsonObject {
            put("service", service)
            put("version", BuildInfo.version)
        }.toString()
}
