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

package ksl.app.dist.config

import kotlinx.serialization.Serializable
import net.peanuuutz.tomlkt.TomlComment

/** Supported database backends. */
@Serializable
enum class DbType { SQLITE, DERBY, POSTGRES }

/**
 * How database credentials are obtained at run time. The connection reference
 * itself never carries secrets — only *references* to where the secret lives
 * (an env-var name, a file path) or a hint to prompt for it.
 */
@Serializable
sealed class CredentialSource {
    /** No credentials required (embedded/file database, or otherwise trusted). */
    @Serializable
    data object None : CredentialSource()

    /** The front-end prompts the user at run time; never persisted. */
    @Serializable
    data class RuntimePrompt(
        @TomlComment("String. Optional username to pre-fill in the run-time prompt.")
        val usernameHint: String? = null
    ) : CredentialSource()

    /** Read the username/password from named environment variables. */
    @Serializable
    data class Environment(
        @TomlComment("String. Name of the environment variable holding the username.")
        val userVar: String,
        @TomlComment("String. Name of the environment variable holding the password.")
        val passwordVar: String
    ) : CredentialSource()

    /**
     * Read credentials from a TOML secrets file (outside the config) with
     * `username` and `password` keys.
     */
    @Serializable
    data class ExternalFile(
        @TomlComment("String. Path to a secrets file (outside this config) with `username` and `password` keys.")
        val path: String
    ) : CredentialSource()
}

/**
 * Credential-free locator for a database connection. Embedded databases
 * (SQLite, Derby) put the file path in [location] and use
 * [CredentialSource.None]. Server databases (Postgres) additionally set
 * [serverName] / [portNumber] and a non-`None` credential source; that path
 * lands in the server-database phase.
 */
@Serializable
data class DatabaseConnectionRef(
    @TomlComment("String. Backend: \"SQLITE\", \"DERBY\", or \"POSTGRES\".")
    val dbType: DbType,
    @TomlComment("String. For embedded databases, the file/directory path; for servers, the database name.")
    val location: String,
    @TomlComment("String. Server host name (server databases only).")
    val serverName: String? = null,
    @TomlComment("Integer. Server port (server databases only).")
    val portNumber: Int? = null,
    @TomlComment(
        "How credentials are obtained: \"None\" (embedded), \"RuntimePrompt\",\n" +
        "\"Environment\" (named env vars), or \"ExternalFile\". Never stores secrets."
    )
    val credentials: CredentialSource = CredentialSource.None
)

/** What to read from the database: a whole table, or the result of a query. */
@Serializable
sealed class DbSource {
    @Serializable
    data class Table(
        @TomlComment("String. Name of the table to read.")
        val name: String
    ) : DbSource()

    @Serializable
    data class Query(
        @TomlComment("String. SQL query whose numeric columns are read into datasets.")
        val sql: String
    ) : DbSource()
}
