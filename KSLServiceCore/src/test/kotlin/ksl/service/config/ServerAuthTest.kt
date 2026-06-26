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

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bearer-token gate policy (the lab/multi-user auth option). The Ktor wiring
 * that calls this lives in each HTTP server module and is exercised by
 * `KslRestAppTest` / `KslMcpHttpServerTest`.
 */
class ServerAuthTest {

    @Test
    fun `no configured token means open (local-trust default)`() {
        assertTrue(ServerAuth.isAuthorized(null, null), "null token: open")
        assertTrue(ServerAuth.isAuthorized("", null), "blank token: open")
        assertTrue(ServerAuth.isAuthorized(null, "Bearer anything"), "null token ignores header")
    }

    @Test
    fun `a configured token requires the matching bearer header`() {
        val token = "s3cret-lab-key"
        assertTrue(ServerAuth.isAuthorized(token, "Bearer $token"), "exact match is allowed")
        assertFalse(ServerAuth.isAuthorized(token, null), "no header is rejected")
        assertFalse(ServerAuth.isAuthorized(token, "Bearer wrong"), "wrong token is rejected")
        assertFalse(ServerAuth.isAuthorized(token, token), "missing 'Bearer ' prefix is rejected")
        assertFalse(ServerAuth.isAuthorized(token, "Bearer ${token}x"), "token must match exactly (no prefix match)")
        assertFalse(ServerAuth.isAuthorized(token, "bearer $token"), "scheme is case-sensitive")
    }

    @Test
    fun `probe paths are public, capability paths are not`() {
        assertTrue(ServerAuth.isPublicPath("/health"))
        assertTrue(ServerAuth.isPublicPath("/ready"))
        assertTrue(ServerAuth.isPublicPath("/version"))
        assertFalse(ServerAuth.isPublicPath("/bundles"))
        assertFalse(ServerAuth.isPublicPath("/"))
    }
}
