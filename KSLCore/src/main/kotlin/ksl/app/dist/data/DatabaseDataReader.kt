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

import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.DatabaseConnectionRef
import ksl.app.dist.config.DatasetLayout
import ksl.app.dist.config.DbSource
import ksl.app.dist.config.DbType
import ksl.utilities.io.dbutil.Database
import ksl.utilities.io.dbutil.DatabaseIfc
import ksl.utilities.io.dbutil.DerbyDb
import ksl.utilities.io.dbutil.PostgresDb
import ksl.utilities.io.dbutil.SQLiteDb
import org.jetbrains.kotlinx.dataframe.*
import org.jetbrains.kotlinx.dataframe.api.*
import java.nio.file.Paths

/**
 * Reads numeric datasets from a database table or query. Resolves the
 * connection, runs the table/query, converts the result set to a Kotlin
 * DataFrame, and extracts numeric columns per the requested layout.
 *
 * Embedded databases (SQLite, Derby) connect by file path and ignore
 * credentials. Server databases (Postgres) resolve credentials at run time
 * via the supplied [CredentialResolver].
 */
object DatabaseDataReader {

    private val NUMERIC_TYPES = setOf(
        Double::class, Float::class, Int::class, Long::class, Short::class, Byte::class
    )

    fun read(
        reference: DataSourceReference.Database,
        resolver: CredentialResolver = DefaultCredentialResolver
    ): List<NamedDataset> {
        val df = queryToDataFrame(reference, resolver)
        return when (reference.layout) {
            DatasetLayout.WIDE -> extractWide(df, reference.datasetColumns)
            DatasetLayout.LONG -> extractLong(
                df,
                idColumn = reference.idColumn
                    ?: throw ImportException("LONG layout requires idColumn"),
                valueColumn = reference.valueColumn
                    ?: throw ImportException("LONG layout requires valueColumn")
            )
            DatasetLayout.SINGLE ->
                throw ImportException("SINGLE layout is not supported for database sources; use WIDE or LONG")
        }
    }

    private fun queryToDataFrame(
        reference: DataSourceReference.Database,
        resolver: CredentialResolver
    ): AnyFrame {
        val db = openDatabase(reference.connection, resolver)
        // Use an open (forward-only) ResultSet for both table and query —
        // DatabaseIfc.toDataFrame wraps it in a CachedRowSet itself, and a
        // CachedRowSet (as returned by selectAll) is not compatible with that.
        val sql = when (val source = reference.source) {
            is DbSource.Table -> "select * from ${source.name}"
            is DbSource.Query -> source.sql
        }
        val resultSet = db.fetchOpenResultSet(sql)
            ?: throw ImportException("database source did not execute: $sql")
        return resultSet.use { DatabaseIfc.toDataFrame(it) }
    }

    private fun openDatabase(
        connection: DatabaseConnectionRef,
        resolver: CredentialResolver
    ): DatabaseIfc {
        return when (connection.dbType) {
            // Embedded: connect by file path; credentials are not applicable.
            DbType.SQLITE -> SQLiteDb.openDatabase(Paths.get(connection.location))
            DbType.DERBY -> DerbyDb.openDatabase(Paths.get(connection.location))
            // Server: resolve credentials at run time, then connect.
            DbType.POSTGRES -> {
                val creds = resolver.resolve(connection)
                val dataSource = PostgresDb.createDataSource(
                    dbServerName = connection.serverName ?: "localhost",
                    dbName = connection.location,
                    user = creds?.username ?: "",
                    pWord = creds?.password ?: "",
                    portNumber = connection.portNumber ?: 5432
                )
                Database(dataSource, connection.location)
            }
        }
    }

    // ----- WIDE -------------------------------------------------------------

    private fun extractWide(df: AnyFrame, filter: List<String>?): List<NamedDataset> {
        val names = filter ?: df.columnNames()
        if (filter != null) {
            val missing = filter.filterNot { it in df.columnNames() }
            if (missing.isNotEmpty()) {
                throw ImportException("requested columns not found: $missing")
            }
        }
        val datasets = mutableListOf<NamedDataset>()
        for (name in names) {
            val data = numericColumn(df, name)
            if (data == null) {
                if (filter != null) {
                    throw ImportException("column '$name' is not numeric")
                }
                continue // auto mode: skip non-numeric columns
            }
            if (data.isEmpty()) {
                if (filter != null) throw ImportException("column '$name' has no numeric values")
                continue
            }
            datasets += NamedDataset(name, data)
        }
        if (datasets.isEmpty()) {
            throw ImportException("no numeric columns found in the result")
        }
        return datasets
    }

    // ----- LONG -------------------------------------------------------------

    private fun extractLong(df: AnyFrame, idColumn: String, valueColumn: String): List<NamedDataset> {
        if (idColumn !in df.columnNames()) {
            throw ImportException("id column '$idColumn' not found in ${df.columnNames()}")
        }
        if (valueColumn !in df.columnNames()) {
            throw ImportException("value column '$valueColumn' not found in ${df.columnNames()}")
        }
        val idValues = df.getColumn(idColumn).toList()
        val valueValues = df.getColumn(valueColumn).toList()
        val ids = ArrayList<String>(idValues.size)
        val values = ArrayList<Double>(idValues.size)
        for (i in valueValues.indices) {
            val v = (valueValues[i] as? Number)?.toDouble() ?: continue // skip null/non-numeric rows
            ids.add(idValues[i]?.toString() ?: "")
            values.add(v)
        }
        if (values.isEmpty()) {
            throw ImportException("value column '$valueColumn' has no numeric values")
        }
        return WideLongReshape.splitLong(ids, DoubleArray(values.size) { values[it] })
    }

    // ----- column extraction ------------------------------------------------

    /**
     * Returns the named column as a DoubleArray when it is numeric (Double,
     * Float, Int, Long, Short, Byte), dropping null cells; null when the
     * column is non-numeric.
     */
    private fun numericColumn(df: AnyFrame, name: String): DoubleArray? {
        val col = df.getColumn(name)
        if (col.typeClass !in NUMERIC_TYPES) return null
        val values = col.toList().mapNotNull { (it as? Number)?.toDouble() }
        return DoubleArray(values.size) { values[it] }
    }
}
