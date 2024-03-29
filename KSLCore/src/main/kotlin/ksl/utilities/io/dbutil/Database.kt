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
import java.nio.file.Path
import java.sql.Connection
import java.util.*
import javax.sql.DataSource

open class Database(
    final override val dataSource: DataSource,
    final override var label: String,
    final override var defaultSchemaName: String? = null
) : DatabaseIfc {

    override var outputDirectory: OutputDirectory = KSL.myOutputDir

    final override fun getConnection(): Connection = super.getConnection()

    override val dbURL: String? = getConnection().use { it.metaData?.url }

    init {
        DatabaseIfc.logger.info { "Constructed DatabaseImp $label via $dbURL" }
    }

//    val db = Database.connect(dataSource, logger = Slf4jLoggerAdapter(DatabaseIfc.logger))

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Database: $label")
        sb.appendLine("The database was connected via url $dbURL")
        sb.appendLine("The database has the following schemas:")
        sb.append("\t")
        sb.append(schemas.toString())
        sb.appendLine()
        sb.appendLine("The default schema is $defaultSchemaName")
        sb.appendLine("The database has the following user defined tables:")
        sb.append("\t")
        sb.append(userDefinedTables.toString())
        sb.appendLine()
        sb.appendLine("The database has the following views:")
        sb.append("\t")
        sb.append(views.toString())
        sb.appendLine()
        return sb.toString()
    }

    companion object {
        /** Helper method for making a database
         *
         * @param dBProperties the properties, must not be null
         * @return the created database
         */
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
        fun dataSource(properties: Properties): DataSource {
            val config = HikariConfig(properties)
            return HikariDataSource(config)
        }

        /**
         * @param pathToPropertiesFile must not be null
         * @return a DataSource for making a database
         */
        fun dataSource(pathToPropertiesFile: Path): DataSource {
            val config = HikariConfig(pathToPropertiesFile.toString())
            return HikariDataSource(config)
        }
    }

}