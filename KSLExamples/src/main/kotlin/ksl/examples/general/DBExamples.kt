package ksl.examples.general

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

    DBExamples.testKSLDatabase()
}

object DBExamples {
    var pathToWorkingDir: Path = Paths.get("").toAbsolutePath()
    var pathToDbExamples: Path = pathToWorkingDir.resolve("dbExamples")

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
        KSLDatabaseObserver(model)

        model.simulate()
        model.print()

        sdb.writeAllTablesAsMarkdown()

        val df = kdb.withinRepViewStatistics
        println(df.schema())
        println(df)

        df.writeMarkDownTable()

        val stat_name by column<String>()
        val exp_name by column<String>()
        println(exp_name.name())
        val filter = df.filter { exp_name().equals("Experiment_1") }.values { stat_name }

        println("Found = " + filter.count())

        val observations = kdb.withinReplicationObservationsFor("Experiment_1", "# in System")
        println(observations.toList())

        val rs = sdb.selectAllIntoOpenResultSet("ACROSS_REP_STAT")
        if (rs != null) {
            val r = DatabaseIfc.toDataFrame(rs)
            println(r)
        }

        sdb.exportAllTablesAsCSV()

        sdb.exportToExcel()

        println("Done!")
    }

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
    fun example1() {
        // This is an embedded Derby database which resides on the disk
        // Derby holds the database (its files) in a directory.
        // This is the full path to where the database will be held.
        // Define the path to the database.
        val pathToDb = pathToDbExamples.resolve("SP_Example_Db")
        // Specify the path as a datasource with true indicating that new database will be created (even if old exists)
        val dataSource: DataSource = DatabaseFactory.createEmbeddedDerbyDataSource(pathToDb = pathToDb, create = true)
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

        // Do a regular SQL select statement as a string and print the results
//        val records: Result<Record> = db.fetchResults("select * from s")
//        // Print them all out
//        records.format(System.out)
//        // iterate through each record, get field data using field name and convert to correct data type
//        for (r in records) {
//            val status: Int = r.get("STATUS", Int::class.java)
//            println(status)
//        }
//        // Get the status data as an Integer array
//        val array: Array<Int> = records.intoArray("STATUS", Int::class.java)
//        // Convert it to a double array if you want
//        val data: DoubleArray = JSLArrayUtil.toDouble(array)
//        // compute some statistics on it
//        System.out.println(Statistic.collectStatistics(data))
    }

    /** Shows how to make a SP database from scripts and then writes the database to an Excel workbook
     *
     * @throws IOException an exception
     */
    fun exampleDbToExcelExport() {
        val dbName = "SP_To_Excel"
        // make the database
        val db: DatabaseIfc = DatabaseFactory.createEmbeddedDerbyDatabase(dbName, pathToDbExamples)
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
        val db: DatabaseIfc = DatabaseFactory.createEmbeddedDerbyDatabase(dbName, pathToDbExamples)

        // builder the creation task
        val tables = pathToDbExamples.resolve("SPDatabase_Tables.sql")
        val inserts = pathToDbExamples.resolve("SPDatabase_Insert.sql")
        val alters = pathToDbExamples.resolve("SPDatabase_Alter.sql")
        val wbPath = pathToDbExamples.resolve("SP_To_DB.xlsx")
        //TODO an error is in here
        // seems to create the database but not populate it
        db.create().withTables(tables)
            .withExcelData(wbPath, Arrays.asList("S", "P", "SP"))
            .withConstraints(alters)
            .execute()
        db.printAllTablesAsText("APP")
    }

    /**
     * This example shows how to create a SP database from a creation script and perform a simple
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
        val dataSource: DataSource = DatabaseFactory.createEmbeddedDerbyDataSource(pathToDb, create = true)
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