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

/** Supported database backends. */
@Serializable
enum class DbType { SQLITE, DERBY, POSTGRES }

/**
 * How database credentials are obtained at run time. The connection reference
 * itself never carries secrets. Embedded/file databases use [None]; the other
 * variants (front-end prompt, environment variables, external secrets file)
 * arrive with the server-database phase.
 */
@Serializable
sealed class CredentialSource {
    /** No credentials required (embedded/file database, or otherwise trusted). */
    @Serializable
    data object None : CredentialSource()
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
    val dbType: DbType,
    val location: String,
    val serverName: String? = null,
    val portNumber: Int? = null,
    val credentials: CredentialSource = CredentialSource.None
)

/** What to read from the database: a whole table, or the result of a query. */
@Serializable
sealed class DbSource {
    @Serializable
    data class Table(val name: String) : DbSource()

    @Serializable
    data class Query(val sql: String) : DbSource()
}
