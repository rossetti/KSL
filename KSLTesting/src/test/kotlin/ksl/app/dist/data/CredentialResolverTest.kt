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

package ksl.app.dist.data

import ksl.app.dist.config.CredentialSource
import ksl.app.dist.config.DatabaseConnectionRef
import ksl.app.dist.config.DbType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CredentialResolverTest {

    private fun ref(credentials: CredentialSource) = DatabaseConnectionRef(
        dbType = DbType.POSTGRES, location = "db", serverName = "localhost", portNumber = 5432, credentials = credentials
    )

    @Test
    fun `None resolves to null`() {
        assertNull(DefaultCredentialResolver.resolve(ref(CredentialSource.None)))
    }

    @Test
    fun `RuntimePrompt resolves to null without a front-end resolver`() {
        assertNull(DefaultCredentialResolver.resolve(ref(CredentialSource.RuntimePrompt("alice"))))
    }

    @Test
    fun `ExternalFile reads username and password from a TOML file`() {
        val file = Files.createTempFile("creds", ".toml")
        Files.writeString(file, "username = \"alice\"\npassword = \"s3cret\"\n")
        val creds = DefaultCredentialResolver.resolve(ref(CredentialSource.ExternalFile(file.toString())))
        assertEquals(DbCredentials("alice", "s3cret"), creds)
    }

    @Test
    fun `ExternalFile with a missing file is an ImportException`() {
        assertThrows<ImportException> {
            DefaultCredentialResolver.resolve(ref(CredentialSource.ExternalFile("/no/such/creds.toml")))
        }
    }

    @Test
    fun `Environment reads the named variables`() {
        // PATH is reliably present in the test environment; use it as a stand-in
        // to prove environment-variable reading works.
        val path = System.getenv("PATH")
        org.junit.jupiter.api.Assumptions.assumeTrue(path != null, "PATH not set in this environment")
        val creds = DefaultCredentialResolver.resolve(ref(CredentialSource.Environment("PATH", "PATH")))
        assertEquals(DbCredentials(path!!, path), creds)
    }

    @Test
    fun `Environment with a missing variable is an ImportException`() {
        val ex = assertThrows<ImportException> {
            DefaultCredentialResolver.resolve(ref(CredentialSource.Environment("KSL_NO_SUCH_VAR_ABC123", "x")))
        }
        assertEquals(true, ex.message!!.contains("KSL_NO_SUCH_VAR_ABC123"))
    }

    @Test
    fun `a front-end resolver can supply credentials for RuntimePrompt`() {
        val frontEnd = CredentialResolver { DbCredentials("prompted-user", "prompted-pass") }
        assertEquals(
            DbCredentials("prompted-user", "prompted-pass"),
            frontEnd.resolve(ref(CredentialSource.RuntimePrompt()))
        )
    }
}
