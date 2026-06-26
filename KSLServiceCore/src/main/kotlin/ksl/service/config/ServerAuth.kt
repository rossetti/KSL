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

import java.security.MessageDigest

/**
 * The bearer-token gate for the HTTP servers (REST + MCP-HTTP) — the policy only;
 * each server module supplies the thin Ktor wiring that calls it. This keeps the
 * security decision in one transport-agnostic, unit-tested place.
 *
 * When no token is configured the servers are **open** (the local-trust default,
 * for single-machine/stdio use). When a token is configured (e.g. a shared lab
 * key via `KSL_AUTH_TOKEN`), every request must carry
 * `Authorization: Bearer <token>` — except the unauthenticated probe paths, so
 * health checks and load balancers keep working without the secret.
 */
object ServerAuth {

    /** Liveness/readiness/version probes that stay reachable without a token. */
    val publicPaths: Set<String> = setOf("/health", "/ready", "/version")

    /** Whether [path] bypasses the token gate (a probe endpoint). */
    fun isPublicPath(path: String): Boolean = path in publicPaths

    /**
     * True when a request is allowed: either no token is configured (open), or the
     * `Authorization` header is exactly `Bearer <token>`. The token comparison is
     * constant-time so a wrong token cannot be recovered byte-by-byte from
     * response timing.
     *
     * @param configuredToken the server's token, or null/blank when auth is off
     * @param authorizationHeader the request's `Authorization` header value, if any
     */
    fun isAuthorized(configuredToken: String?, authorizationHeader: String?): Boolean {
        if (configuredToken.isNullOrBlank()) return true
        val prefix = "Bearer "
        val presented = authorizationHeader
            ?.takeIf { it.startsWith(prefix) }
            ?.substring(prefix.length)
            ?: return false
        return MessageDigest.isEqual(
            presented.toByteArray(Charsets.UTF_8),
            configuredToken.toByteArray(Charsets.UTF_8),
        )
    }

    /** The body returned with a `401`. */
    fun unauthorizedJson(): String = """{"error":"unauthorized"}"""
}
