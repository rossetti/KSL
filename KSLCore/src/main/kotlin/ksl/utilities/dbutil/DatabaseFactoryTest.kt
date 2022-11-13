package ksl.utilities.dbutil

class DatabaseFactoryTest {

}

fun main() {
    testSQLite()
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
}