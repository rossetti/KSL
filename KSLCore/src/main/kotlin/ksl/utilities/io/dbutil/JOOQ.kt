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

import ksl.utilities.io.KSLFileUtil
import org.jooq.SQLDialect
import org.jooq.codegen.GenerationTool
import org.jooq.meta.jaxb.*
import org.jooq.meta.jaxb.Target
import org.jooq.tools.jdbc.JDBCUtils
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.SQLException
import java.util.*
import javax.sql.DataSource


object JOOQ {
    init {
        System.setProperty("org.jooq.no-logo", "true")
    }

    /**
     * Runs jooq code generation on the database at the supplied creation script.
     * Places generated source files in named package with the main kotlin source
     *
     * @param pathToCreationScript a path to a valid database creation script, must not be null
     * @param schemaName           the name of the schema for which tables need to be generated, must not be null
     * @param pkgDirName           the directory that holds the target package, must not be null
     * @param packageName          name of package to be created to hold generated code, must not be null
     */
    fun jooqKotlinCodeGeneration(
        pathToCreationScript: Path,
        schemaName: String,
        pkgDirName: String,
        packageName: String
    ) {
        val configuration = Configuration()
        configuration.withGenerator(
            Generator()
                .withName("org.jooq.codegen.KotlinGenerator")
                .withDatabase(
                    Database()
                        .withName("org.jooq.meta.ddl.DDLDatabase")
                        .withInputSchema(schemaName)
                        .withProperties(
                            Property()
                                .withKey("scripts")
                                .withValue(pathToCreationScript.toString())
                        )
                )
                .withTarget(Target().withPackageName(packageName).withDirectory(pkgDirName))
        )
        val tool = GenerationTool()
        tool.run(configuration)
    }

    /**
     * Runs jooq code generation on the database at the supplied path.  Assumes
     * that the database exists and has well-defined structure.  Places generated
     * source files in named package with the main java source
     *
     * @param dataSource  a DataSource that can provide a connection to the database, must not be null
     * @param schemaName  the name of the schema for which tables need to be generated, must not be null
     * @param pkgDirName  the directory that holds the target package, must not be null
     * @param packageName name of package to be created to hold generated code, must not be null
     */
    fun jooqKotlinCodeGenerationDerbyDatabase(
        dataSource: DataSource,
        schemaName: String,
        pkgDirName: String,
        packageName: String
    ) {
        val connection = dataSource.connection
        val configuration = Configuration()
            .withGenerator(
                Generator()
                    .withName("org.jooq.codegen.KotlinGenerator")
                    .withDatabase(
                        Database()
                            .withName("org.jooq.meta.derby.DerbyDatabase")
                            .withInputSchema(schemaName)
                    )
                    .withTarget(
                        Target()
                            .withPackageName(packageName)
                            .withDirectory(pkgDirName)
                    )
            )
//        configuration.generator.generate = Generate().withPojos(true).withDaos(true)
//            .withPojosAsKotlinDataClasses(true)
        val tool = GenerationTool()
        tool.setConnection(connection)
        tool.run(configuration)
    }

    fun runCodeGenerationUsingEmptyDerbyDb() {
        // make the database
        val dbName = "tmpJSLDb"
        println("Making database: $dbName")
        val dbPath: Path = KSLDatabase.dbDir.resolve(dbName)
        KSLFileUtil.deleteDirectory(dbPath.toFile())
        val createScript = Paths.get("src").resolve("main").resolve("resources").resolve("KSL_Db.sql")
        val db = DatabaseFactory.createEmbeddedDerbyDatabase(dbName, dbPath)
        db.create().withCreationScript(createScript).execute()
        println("Created database: $dbPath")
        println("Running code generation.")
        jooqKotlinCodeGenerationDerbyDatabase(
            db.dataSource, "KSL_DB",
            "src/main/kotlin", "ksl.utilities.io.dbutil.ksldbjooq"
        )
        println("Completed code generation.")
        println("Deleting the database")
        KSLFileUtil.deleteDirectory(dbPath.toFile())
    }


    /**  Attempts to determine the SQLDialect for the data source
     * [ Reference to JDBCUtils](https://www.jooq.org/javadoc/latest/org/jooq/tools/jdbc/JDBCUtils.html#dialect-java.sql.Connection-)
     *
     * @param dataSource the data source, must not null
     * @return the SQLDialect. If SQLDialect.DEFAULT is returned then a determination could not be made
     */
    fun getSQLDialect(dataSource: DataSource): SQLDialect {
        var dialect: SQLDialect = SQLDialect.DEFAULT
        return try {
            val connection = dataSource.connection
            dialect = JDBCUtils.dialect(connection)
            connection.close()
            dialect
        } catch (e: SQLException) {
            DatabaseIfc.logger.warn("Could not establish connection to data sources to determine SQLDialect")
            dialect
        }
    }



}

fun main (){
    JOOQ.runCodeGenerationUsingEmptyDerbyDb()
}