package ksl.examples.general.utilities

import kotlinx.datetime.Clock
import ksl.examples.book.chapter4.DriveThroughPharmacy
import ksl.simulation.Model
import ksl.utilities.io.dbutil.*
import ksl.utilities.io.writeMarkDownTable
import ksl.utilities.random.rvariable.ExponentialRV
import org.jetbrains.kotlinx.dataframe.api.column
import org.jetbrains.kotlinx.dataframe.api.filter
import org.jetbrains.kotlinx.dataframe.api.schema
import org.jetbrains.kotlinx.dataframe.api.values
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import javax.sql.DataSource

/**
 * The purpose of this class is to provide a series of examples that utilize some functionality within
 * the dbutil package
 */

fun main() {

//    DBExamples.createKSLDatabases()

//    DBExamples.createDerbyDatabase("TestDerby")
    DBExamples.testKSLDatabase()

//    DBExamples.exampleExcelDbImport()
}

object DBExamples {
    var pathToWorkingDir: Path = Paths.get("").toAbsolutePath()
    var pathToDbExamples: Path = pathToWorkingDir.resolve("dbExamples")

    /**
     *  Creates an empty embedded Derby database called [dbName]
     *  in the kslOutput/dbDir
     */
    fun createDerbyDatabase(dbName: String){
        val ds = DerbyDb.createDataSource(dbName, create = true)
        val db = Database(ds, dbName)
  //      db.defaultSchemaName = "APP"

        val list = db.tableNames("APP")
        println(list.toString())

        println(db)
    }

    /**
     *   Uses the DriveThroughPharmacy model to create and attach multiple
     *   databases and executes the model. Output from the databases are written
     *   to markdown, Excel, and to CSV files
     */
    fun testKSLDatabase() {
        val model = Model("Drive Through Pharmacy", autoCSVReports = false)
        model.numberOfReplications = 10
        model.lengthOfReplication = 20000.0
        model.lengthOfReplicationWarmUp = 5000.0
        // add DriveThroughPharmacy to the main model
        val dtp = DriveThroughPharmacy(model, 1)
        dtp.arrivalRV.initialRandomSource = ExponentialRV(6.0, 1)
        dtp.serviceRV.initialRandomSource = ExponentialRV(3.0, 2)

        // this creates and attaches a KSLDatabase
//        val sdb = KSLDatabase.createSQLiteKSLDatabase("TestSQLiteKSLDb")
        val sdb = KSLDatabase.createEmbeddedDerbyKSLDatabase("TestDerbyKSLDb", model.outputDirectory.dbDir)
//        val sdb = KSLDatabase.createPostgreSQLKSLDatabase(dbName = "postgres")
        val kdb = KSLDatabase(sdb)
        KSLDatabaseObserver(model, kdb)
        // this also creates and attached another KSLDatabase, using the defaults
        val adb = KSLDatabaseObserver(model)

        model.simulate()
        model.print()

//        println("Exporting from sqlite to Excel")
//        adb.db.exportToExcel()

        sdb.writeAllTablesAsMarkdown()

        println()
        val df = kdb.withinRepViewStatistics
        println(df.schema())
        println(df)

        df.writeMarkDownTable()

        val stat_name by column<String>()
        val exp_name by column<String>()
        println(exp_name.name())
        val filter = df.filter { exp_name.name() == "Experiment_1" }.values { stat_name }

        println("Found = " + filter.count())

        val observations = kdb.withinReplicationObservationsFor("Experiment_1", "# in System")
        println(observations.toList())

        val rs = sdb.selectAllIntoOpenResultSet("ACROSS_REP_STAT")
        if (rs != null) {
            val r = DatabaseIfc.toDataFrame(rs)
            println(r)
        }

        println("Exporting from db to csv")
        sdb.exportAllTablesAsCSV()
        println("Exporting from db to Excel")
        sdb.exportToExcel()

        println("Done!")
    }

    /**
     *  Illustrates the creation of a blank KSLDatabase based on SQLite and Derby
     */
    fun createKSLDatabases() {
        val sdb = KSLDatabase.createSQLiteKSLDatabase("TestSQLiteKSLDb")
        println("created SQLite based KSLDatabase")
        println(sdb)
        println()
        val ddb = KSLDatabase.createEmbeddedDerbyKSLDatabase("TestDerbyKSLDb")
        println("created Derby based KSLDatabase")
        println(ddb)
        println()
    }

    /**
     * This example shows how to create a new database from a creation script and perform some simple
     * operations on the database
     */
    fun createDbViaCreationScript() {
        // This is an embedded Derby database which resides on the disk
        // Derby holds the database (its files) in a directory.
        // This is the full path to where the database will be held.
        // Define the path to the database.
        val pathToDb = pathToDbExamples.resolve("SP_Example_Db")
        // Specify the path as a datasource with true indicating that new database will be created (even if old exists)
        val dataSource: DataSource = DerbyDb.createDataSource(pathToDb = pathToDb, create = true)
        // Now, make the database from the data source
        val db = Database(dataSource, "SP_Example_Db")
        // We have only established the database, but there isn't anything in it.
        // Specify the path to the full SQL script file that will create the database structure and fill it.
        val script = pathToDbExamples.resolve("SPDatabase_FullCreate.sql")
        // Create a database creation execution task and execute it.
        val task: DbCreateTask = db.create().withCreationScript(script).execute()
        // You can print out the task to illustrate what it is
        // println(task);
        // You can even print out the script commands
        task.creationScriptCommands.forEach(::println)
        // Perform a simple select * command on the table SP
        db.printAllTablesAsText("APP")
    }

    /** Shows how to make a SP database from scripts and then writes the database to an Excel workbook
     *
     * @throws IOException an exception
     */
    fun exampleDbToExcelExport() {
        val dbName = "SP_To_Excel"
        // make the database
        val db: DatabaseIfc = DerbyDb.createDatabase(dbName, pathToDbExamples)
        // build the creation task
        val tables = pathToDbExamples.resolve("SPDatabase_Tables.sql")
        val inserts = pathToDbExamples.resolve("SPDatabase_Insert.sql")
        val alters = pathToDbExamples.resolve("SPDatabase_Alter.sql")
        val task: DbCreateTask = db.create().withTables(tables)
            .withInserts(inserts)
            .withConstraints(alters)
            .execute()
        println(task)
        db.printAllTablesAsText("APP")
        db.exportToExcel("APP", "${dbName}_${Clock.System.now()}", pathToDbExamples)
    }

    /** Shows how to create the SP database by importing from an Excel workbook
     *
     * @throws IOException the IO exception
     */
    fun exampleExcelDbImport() {
        val dbName = "SP_From_Excel"
        // make the database
        val db: DatabaseIfc = DerbyDb.createDatabase(dbName, pathToDbExamples)

        // builder the creation task
        val tables = pathToDbExamples.resolve("SPDatabase_Tables.sql")
        val inserts = pathToDbExamples.resolve("SPDatabase_Insert.sql")
        val alters = pathToDbExamples.resolve("SPDatabase_Alter.sql")
        val wbPath = pathToDbExamples.resolve("SP_To_DB.xlsx")
        db.create().withTables(tables)
            .withExcelData(wbPath, listOf("S", "P", "SP"))
            .withConstraints(alters)
            .execute()
        db.printAllTablesAsText("APP")
    }

    /**
     * This example shows how to create the SP database from a creation script and perform a simple
     * operation on the database.
     *
     * @return the created database
     */
    fun exampleSPCreationFromFullScript(): DatabaseIfc {
        // This is an embedded Derby database which resides on the disk
        // Derby holds the database (its files) in a directory.
        // This is the full path to where the database will be held.
        // Define the path to the database.
        val pathToDb = pathToDbExamples.resolve("SP_FullCreate_Db")
        // Specify the path as a datasource with true indicating that new database will be created (even if old exists)
        val dataSource: DataSource = DerbyDb.createDataSource(pathToDb, create = true)
        // Now, make the database from the data source
        val db = Database(dataSource, "SP_FullCreate_Db")
        // We have only established the database, but there isn't anything in it.
        // Specify the path to the full SQL script file that will create the database structure and fill it.
        val script = pathToDbExamples.resolve("SPDatabase_FullCreate.sql")
        // Create a database creation execution task and execute it.
        val task: DbCreateTask = db.create().withCreationScript(script).execute()
        // Perform a simple select * command on the table SP
        db.printTableAsText(tableName = "SP", schemaName = "APP")
        return db
    }
}