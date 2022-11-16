package ksl.examples.general

import ksl.utilities.dbutil.Database
import ksl.utilities.dbutil.DatabaseFactory
import ksl.utilities.dbutil.DatabaseIfc
import ksl.utilities.dbutil.DbCreateTask
import java.nio.file.Paths
import java.util.*
import javax.sql.DataSource

/**
 * The purpose of this class is to provide a series of examples that utilize some of the functionality within
 * the dbutil package
 */

fun main(args: Array<String>) {
    // This example creates a Derby database called SP_Example_Db within the dbExamples folder
    println()
    println("*** example1 output:")
    DBExamples.example1()
    // This example creates a Derby database called SP_To_Excel within the dbExamples folder and exports it to Excel
    println()
    println("*** exampleDbToExcelExport output:")
    DBExamples.exampleDbToExcelExport()
    // This example reads an Excel work book holding the SP database information and makes a Derby database
    println()
    println("*** exampleExcelDbImport output:")
    DBExamples.exampleExcelDbImport()
    // This example creates the SP database and prints out the SP table
    println()
    println("*** exampleSPCreationFromFullScript output:")
    DBExamples.exampleSPCreationFromFullScript()
}

object DBExamples {
    var pathToWorkingDir = Paths.get("").toAbsolutePath()
    var pathToDbExamples = pathToWorkingDir.resolve("dbExamples")

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
        val db = Database(dataSource,"SP_Example_Db")
        // We have only established the database, but there isn't anything in it.
        // Specify the path to the full SQL script file that will create the database structure and fill it.
        val script = pathToDbExamples.resolve("SPDatabase_FullCreate.sql")
        // Create a database creation execution task and execute it.
        val task: DbCreateTask = db.create().withCreationScript(script).execute()
        // You can print out the task to illustrate what it is
        // System.out.println(task);
        // You can even print out the script commands
        task.creationScriptCommands.forEach(System.out::println)
        // Perform a simple select * command on the table SP
        db.printAllTablesAsText("SP")
//TODO        db.selectAll("SP").format(System.out)
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
        // builder the creation task
        val tables = pathToDbExamples.resolve("SPDatabase_Tables.sql")
        val inserts = pathToDbExamples.resolve("SPDatabase_Insert.sql")
        val alters = pathToDbExamples.resolve("SPDatabase_Alter.sql")
        val task: DbCreateTask = db.create().withTables(tables)
            .withInserts(inserts)
            .withConstraints(alters)
            .execute()
        System.out.println(task)
        db.writeDbToExcelWorkbook("APP", dbName, pathToDbExamples)
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
        val db = Database(dataSource,"SP_FullCreate_Db")
        // We have only established the database, but there isn't anything in it.
        // Specify the path to the full SQL script file that will create the database structure and fill it.
        val script = pathToDbExamples.resolve("SPDatabase_FullCreate.sql")
        // Create a database creation execution task and execute it.
        val task: DbCreateTask = db.create().withCreationScript(script).execute()
        // Perform a simple select * command on the table SP
        db.printTableAsText(schemaName = "APP", tableName = "SP")
        return db
    }
}