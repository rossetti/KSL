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

package ksl.utilities.io.dbutil

//import org.ktorm.database.Database
//import org.ktorm.logging.Slf4jLoggerAdapter
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import ksl.utilities.io.KSL
import ksl.utilities.io.OutputDirectory
import org.apache.derby.jdbc.EmbeddedDataSource
import java.nio.file.Path
import java.sql.Connection
import java.util.*
import javax.sql.DataSource

open class Database @JvmOverloads constructor(
    final override val dataSource: DataSource,
    final override var label: String,
    final override var defaultSchemaName: String? = null
) : DatabaseIfc {

    final override var outputDirectory: OutputDirectory = KSL.myOutputDir

    private val lazyLongLastingConnection: Lazy<Connection> = lazy { getConnection() }
    final override val longLastingConnection: Connection
        get() = lazyLongLastingConnection.value

    @Volatile
    private var closed: Boolean = false

    /**
     * Releases the resources this database holds so its backing files can be deleted or replaced.
     * Idempotent. After close the database is unusable: metadata and query operations route
     * through [longLastingConnection], which is now closed.
     *
     * - Closes the single long-lived JDBC connection ([longLastingConnection]) if it was ever
     *   opened. A held connection is what locks the SQLite `.db` file on Windows; closing it
     *   releases the file.
     * - For an embedded Derby database, additionally issues the engine shutdown, because Derby
     *   keeps the database booted — holding `db.lck` — after every connection is closed, until an
     *   explicit `;shutdown=true`.
     * - If [dataSource] is a pool (a HikariCP `HikariDataSource`, used by the properties/Postgres
     *   path), closes it too. The plain embedded SQLite/Derby data sources are not `AutoCloseable`,
     *   so this is a no-op for them.
     */
    override fun close() {
        if (closed) return
        closed = true
        if (lazyLongLastingConnection.isInitialized()) {
            runCatching { lazyLongLastingConnection.value.close() }
                .onFailure { DatabaseIfc.logger.warn(it) { "Failed to close the long-lasting connection for database $label" } }
        }
        (dataSource as? EmbeddedDataSource)?.let { eds ->
            runCatching { DerbyDb.shutDownDatabase(Path.of(eds.databaseName)) }
                .onFailure { DatabaseIfc.logger.warn(it) { "Failed to shut down embedded Derby database ${eds.databaseName}" } }
        }
        (dataSource as? AutoCloseable)?.let { pool ->
            runCatching { pool.close() }
                .onFailure { DatabaseIfc.logger.warn(it) { "Failed to close the data source for database $label" } }
        }
    }

    final override fun getConnection(): Connection = super.getConnection()

    final override val dbURL: String? = getConnection().use { it.metaData?.url }

    init {
        DatabaseIfc.logger.info { "Initialized Database $label with URL = $dbURL" }
    }

//    val db = Database.connect(dataSource, logger = Slf4jLoggerAdapter(DatabaseIfc.logger))

    override fun toString(): String {
        return asString()
    }

    companion object {

        //    val nonUserDefinedSysSchemas = setOf("SYS", "SYSIBM", "pg_catalog", "information_schema", "pg_toast" )

        /** This constructs a database on disk.
         * The database will contain empty tables based on the table definitions.
         *
         * @param tableDefinitions an example set of table definitions based on DbTableData specifications
         * @param dbName the name of the database
         * @param dbDirectory the directory containing the database. By default, KSL.dbDir.
         * @param deleteIfExists If true, an existing database in the supplied directory with
         * the same name will be deleted and an empty database will be constructed.
         * @param dbType The type of database (SQLite, Derby, DuckDb). The default is SQLite.
         * @return a database
         */
        @JvmOverloads
        @JvmStatic
        fun createSimpleDb(
            tableDefinitions: Set<DbTableData>,
            dbName: String,
            dbDirectory: Path = KSL.dbDir,
            deleteIfExists: Boolean = true,
            dbType: EmbeddedDbType = EmbeddedDbType.SQLITE
        ): Database {
            return when (dbType) {
                EmbeddedDbType.SQLITE -> {
                    SQLiteDb(tableDefinitions, dbName, dbDirectory, deleteIfExists)
                }
                EmbeddedDbType.DERBY -> {
                    DerbyDb(tableDefinitions, dbName, dbDirectory, deleteIfExists)
                }
            }
        }

        /** Helper method for making a database
         *
         * @param dBProperties the properties, must not be null
         * @return the created database
         */
        @JvmStatic
        fun createDatabaseFromProperties(dBProperties: Properties): Database {
            val ds: DataSource = dataSource(dBProperties)
            val user = dBProperties.getProperty("dataSource.user")
            val name = dBProperties.getProperty("dataSource.databaseName")
            val dbLabel = user + "_" + name
            return Database(ds, dbLabel)
        }

        /**
         * Assumes that the properties are appropriately configured to create a DataSource
         * via  HikariCP
         *
         * @param properties the properties
         * @return a pooled connection DataSource
         */
        @JvmStatic
        fun dataSource(properties: Properties): DataSource {
            val config = HikariConfig(properties)
            return HikariDataSource(config)
        }

        /**
         * @param pathToPropertiesFile must not be null
         * @return a DataSource for making a database
         */
        @JvmStatic
        fun dataSource(pathToPropertiesFile: Path): DataSource {
            val config = HikariConfig(pathToPropertiesFile.toString())
            return HikariDataSource(config)
        }

    }

}