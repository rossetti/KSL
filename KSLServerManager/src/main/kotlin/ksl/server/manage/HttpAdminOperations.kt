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

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import ksl.service.admin.ServerAdminOperations
import ksl.service.admin.SuiteStatus
import ksl.service.usage.UsageEvent
import ksl.service.usage.UsageSummary
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The HTTP implementation of [ServerAdminOperations]: reads a running suite's admin surface over
 * `/admin` (`/status`, `/admin/usage`, `/admin/activity`) and parses the SAME `@Serializable` DTOs the
 * suite serves in-process, so an external UI / CLI renders exactly what the built-in console does.
 *
 * Calls are blocking (plain JDK http client), matching the non-suspend contract; a caller that must
 * not block (a UI thread) invokes these on a background dispatcher — which the `ServerManagerController`
 * does. Every method may throw when the suite is unreachable; the controller catches and reflects that
 * as a DOWN health state.
 */
class HttpAdminOperations(
    baseUrl: String = "http://127.0.0.1:3001",
    private val authToken: String? = null,
    private val timeout: Duration = Duration.ofSeconds(3),
) : ServerAdminOperations {

    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder().connectTimeout(timeout).build()

    override fun status(): SuiteStatus =
        json.decodeFromString(SuiteStatus.serializer(), get("/status"))

    override fun usageSummary(): UsageSummary =
        json.decodeFromString(UsageSummary.serializer(), get("/admin/usage"))

    override fun recentActivity(limit: Int): List<UsageEvent> =
        json.decodeFromString(ListSerializer(UsageEvent.serializer()), get("/admin/activity?limit=$limit"))

    private fun get(path: String): String {
        val builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout).GET()
        authToken?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        val resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        require(resp.statusCode() in 200..299) { "admin $path: HTTP ${resp.statusCode()}" }
        return resp.body()
    }
}
