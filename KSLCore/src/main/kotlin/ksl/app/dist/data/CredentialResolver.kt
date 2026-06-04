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

import kotlinx.serialization.Serializable
import ksl.app.dist.config.CredentialSource
import ksl.app.dist.config.DatabaseConnectionRef
import net.peanuuutz.tomlkt.Toml
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Resolved database credentials. Deliberately NOT serializable — secrets are
 * resolved at run time and never cross the wire or land in a config.
 */
data class DbCredentials(val username: String, val password: String)

/**
 * Resolves the credentials for a database connection at run time, from the
 * credential-free reference. Front-ends supply their own resolver (e.g. a
 * Swing dialog for `RuntimePrompt`), typically delegating the non-interactive
 * cases to [DefaultCredentialResolver].
 */
fun interface CredentialResolver {
    /** Returns credentials for the reference, or null when none are needed/available. */
    fun resolve(ref: DatabaseConnectionRef): DbCredentials?
}

/**
 * Non-interactive credential resolution:
 *  - [CredentialSource.None] → null (no credentials needed)
 *  - [CredentialSource.Environment] → reads the named environment variables
 *    (a missing variable is an [ImportException])
 *  - [CredentialSource.ExternalFile] → reads a TOML file with `username` and
 *    `password` keys
 *  - [CredentialSource.RuntimePrompt] → null (no prompt available headless; a
 *    front-end resolver overrides this case)
 */
object DefaultCredentialResolver : CredentialResolver {

    @Serializable
    private data class TomlDbCredentials(val username: String, val password: String)

    private val toml = Toml { ignoreUnknownKeys = true }

    override fun resolve(ref: DatabaseConnectionRef): DbCredentials? {
        return when (val source = ref.credentials) {
            is CredentialSource.None -> null
            is CredentialSource.RuntimePrompt -> null
            is CredentialSource.Environment -> {
                val user = System.getenv(source.userVar)
                    ?: throw ImportException("environment variable '${source.userVar}' is not set")
                val password = System.getenv(source.passwordVar)
                    ?: throw ImportException("environment variable '${source.passwordVar}' is not set")
                DbCredentials(user, password)
            }
            is CredentialSource.ExternalFile -> {
                val path = Paths.get(source.path)
                if (!Files.exists(path)) {
                    throw ImportException("credentials file not found: ${source.path}")
                }
                val parsed = try {
                    toml.decodeFromString(TomlDbCredentials.serializer(), Files.readString(path))
                } catch (e: Exception) {
                    throw ImportException("failed to read credentials TOML '${source.path}': ${e.message}", e)
                }
                DbCredentials(parsed.username, parsed.password)
            }
        }
    }
}
