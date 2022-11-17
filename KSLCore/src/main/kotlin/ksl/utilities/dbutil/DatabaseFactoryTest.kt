package ksl.utilities.dbutil

import java.nio.file.Paths

class DatabaseFactoryTest {

}

fun main() {
    testSQLite()
//    testSQLite2()
//    testKTorm()
//    testCreateDerbyDbCreation()

//    testDatabaseCreation()
}

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
}

fun testKTorm(){
    val db = DatabaseFactory.getSQLiteDatabase("someDB.db") as Database
//    println(db.ktormDb)
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