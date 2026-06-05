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

package ksl.app.swing.dist.panel

import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.DatabaseConnectionRef
import ksl.app.dist.config.DatasetLayout
import ksl.app.dist.config.DbSource
import ksl.app.dist.config.DbType
import ksl.utilities.io.dbutil.DatabaseIfc
import ksl.utilities.io.dbutil.DerbyDb
import ksl.utilities.io.dbutil.SQLiteDb
import java.nio.file.Path
import java.sql.Types

/**
 * Builders for the embedded-database data source. Keeps the database access and
 * the form-to-reference mapping out of the Swing widget so both are testable.
 * Only embedded databases (SQLite, Derby) with a table source are handled here;
 * server databases and query sources are out of scope for this card.
 */
object DatabaseSource {

    /** One column of a database table: its name and whether it holds numeric data. */
    data class DbColumn(val name: String, val numeric: Boolean)

    private val NUMERIC_JDBC_TYPES = setOf(
        Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
        Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL
    )

    private fun openReadOnly(dbType: DbType, path: Path): DatabaseIfc = when (dbType) {
        DbType.SQLITE -> SQLiteDb.openDatabaseReadOnly(path)
        DbType.DERBY -> DerbyDb.openDatabase(path)
        DbType.POSTGRES -> error("Only embedded databases (SQLite, Derby) are supported here")
    }

    /**
     * Opens the embedded database at [path] read-only and returns its user
     * tables mapped to their columns (name + whether numeric), so the form can
     * let the user *select* a table and its id/value/import columns. The
     * metadata connection is closed before returning.
     */
    fun listTablesWithColumns(dbType: DbType, path: Path): Map<String, List<DbColumn>> {
        val db = openReadOnly(dbType, path)
        return try {
            val out = sortedMapOf<String, List<DbColumn>>()
            for ((schema, tables) in db.userDefinedTables) {
                for (table in tables) {
                    out[table] = runCatching {
                        db.tableMetaData(table, schema).map { DbColumn(it.name, it.type in NUMERIC_JDBC_TYPES) }
                    }.getOrDefault(emptyList())
                }
            }
            out
        } finally {
            runCatching { db.longLastingConnection.close() }
        }
    }

    /**
     * Returns the first [maxRows] rows of [table] (including the column header)
     * as a data-frame-formatted string for an at-a-glance preview, using
     * [DatabaseIfc.fetchOpenResultSet] + [DatabaseIfc.toDataFrame]. The result set
     * and the connection are closed before returning.
     */
    fun previewTable(dbType: DbType, path: Path, table: String, maxRows: Int = 5): String {
        val db = openReadOnly(dbType, path)
        return try {
            val rs = db.fetchOpenResultSet("select * from \"$table\" limit $maxRows")
                ?: return "(could not read table '$table')"
            rs.use { DatabaseIfc.toDataFrame(it).toString() }
        } finally {
            runCatching { db.longLastingConnection.close() }
        }
    }

    /**
     * Builds a [DataSourceReference.Database] reading [tableName] from the
     * embedded database at [location]. For WIDE supply [datasetColumns] (or null
     * for every numeric column); for LONG supply [idColumn] and [valueColumn].
     */
    fun buildRef(
        dbType: DbType,
        location: String,
        tableName: String,
        layout: DatasetLayout,
        datasetColumns: List<String>?,
        idColumn: String?,
        valueColumn: String?
    ): DataSourceReference.Database =
        DataSourceReference.Database(
            connection = DatabaseConnectionRef(dbType = dbType, location = location),
            source = DbSource.Table(tableName),
            layout = layout,
            idColumn = idColumn,
            valueColumn = valueColumn,
            datasetColumns = datasetColumns
        )
}
