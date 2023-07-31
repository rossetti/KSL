package ksl.examples.general

import ksl.utilities.io.dbutil.Database
import ksl.utilities.io.dbutil.DatabaseFactory
import ksl.utilities.io.dbutil.KSLDatabase
import java.nio.file.Path
import java.nio.file.Paths
import javax.sql.DataSource

class DatabaseFactoryTest {

}
var pathToWorkingDir: Path = Paths.get("").toAbsolutePath()
var pathToDbExamples: Path = pathToWorkingDir.resolve("dbExamples")

fun main() {
   testSQLite()
//    testDuckDb()
//    testSQLite2()
//    testCreateDerbyDbCreation()

//    testDatabaseCreation()

    // the postgres tests assume that postgres is installed on the local machine
    // and there is a user called test with pw test with appropriate privileges on the server
//    testPostgresLocalHost()
//    testPostgresLocalHostKSLDb()
//    createPostgresLocalHostKSLDb()
}

//fun testDuckDb() {
//    val database = DatabaseFactory.createDuckDbDatabase("someDB.db")
//    val b = database.executeCommand("drop table if exists person")
//    database.executeCommand("create table person (id integer, name string)")
//    println(database)
//    val allTableNames: List<String> = database.userDefinedTables
//    for (s in allTableNames) {
//        println("Table: $s")
//    }
//    database.executeCommand("insert into person values(1, 'PersonA')")
//    database.executeCommand("insert into person values(2, 'PersonB')")
//    database.printTableAsText(tableName = "person")
//    println()
//    database.printTableAsMarkdown(tableName = "person")
//    database.exportToExcel(tableNames = listOf("person"))
//    database.printInsertQueries(tableName = "person")
//}

fun testSQLite() {
    val database = DatabaseFactory.createSQLiteDatabase("someDB.db")
    database.executeCommand("drop table if exists person")
    database.executeCommand("create table person (id integer, name string)")
    println(database)
    val allTableNames: List<String> = database.userDefinedTables
    for (s in allTableNames) {
        println("Table: $s")
    }
    database.executeCommand("insert into person values(1, 'PersonA')")
    database.executeCommand("insert into person values(2, 'PersonB')")
    database.printTableAsText(tableName = "person")
    println()
    database.printTableAsMarkdown(tableName = "person")
    database.exportToExcel(tableNames = listOf("person"))
    database.printInsertQueries(tableName = "person")
}

fun testSQLite2() {
    val database = DatabaseFactory.getSQLiteDatabase("someDB.db")
    database.printTableAsText(tableName = "person")
    println("Done!")
}

fun testDatabaseCreation() {
    val path = Paths.get("/Users/rossetti/Documents/Development/Temp")
    val name = "manuel"
    val database = DatabaseFactory.createEmbeddedDerbyDatabase(name, path)
    println(database)
}

fun testCreateDerbyDbCreation(){
    val db = DatabaseFactory.createEmbeddedDerbyDatabase("TestSPDb")
    println(db)
}

fun testPostgresLocalHost() {
    val dbName = "test"
    val user = "test"
    val pw = "test"
    val dataSource: DataSource = DatabaseFactory.postgreSQLDataSourceWithLocalHost(dbName, user, pw)
    // make the database
    val db = Database(dataSource, dbName)
    // builder the creation task
    val pathToCreationScript: Path = pathToDbExamples.resolve("SPDatabase_Postgres.sql")
    val task = db.create().withCreationScript(pathToCreationScript).execute()
    System.out.println(task)
    task.creationScriptCommands.forEach(System.out::println)
    db.printTableAsText("s")
}

fun testPostgresLocalHostKSLDb() {
    val dbName = "test"
    val user = "test"
    val pw = "test"
    val dataSource: DataSource = DatabaseFactory.postgreSQLDataSourceWithLocalHost(dbName, user, pw)
    // make the database
    val db = Database(dataSource, dbName)
    db.executeCommand("DROP SCHEMA IF EXISTS ksl_db CASCADE")
    // builder the creation task
    val pathToCreationScript: Path = pathToDbExamples.resolve("KSL_Db.sql")
    val task = db.create().withCreationScript(pathToCreationScript).execute()
    System.out.println(task)
    task.creationScriptCommands.forEach(System.out::println)
    db.printTableAsText("simulation_run", "ksl_db")

}

fun createPostgresLocalHostKSLDb(){
    val db = KSLDatabase.createPostgreSQLKSLDatabase(dbName = "test", user = "test", pWord = "test")
    db.printAllTablesAsText()
}