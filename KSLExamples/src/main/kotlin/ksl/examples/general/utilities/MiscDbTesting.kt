package ksl.examples.general.utilities

import ksl.utilities.io.KSL
import ksl.utilities.io.dbutil.*
import ksl.utilities.io.dbutil.DuckDb
import java.nio.file.Path
import javax.sql.DataSource

fun main() {
    //   testDbDataCreateString()
//    testSimpleDb()

//    testSQLiteDb()
//    testDerbyDb()

//    testPostgres()
    testDuckDb()

//    testDuckDbParquetFiles()
//    testConvertToDuckDb()

}

fun testDbData() {
    val e = ExperimentTableData()
    val names = e.extractPropertyNames()
    println(names)
    val values = e.extractPropertyValues()
    println(values)
//    val sList = listOf<Any?>(-1, "a", "b", "c", -1, false, null, null, null, true, false, false, true, 100, false)
    val sList = listOf<Any?>(1, "a", "b", "c", 1, 100.0, 5.0, null, true, false, true, false, 0, false)
    e.setPropertyValues(sList)
    println(e)

    val fields = listOf("A", "B", "C")
    val where = listOf("D", "E")
    val sql = DatabaseIfc.updateTableStatementSQL("baseball", fields, where, "league")
    println(sql)

    println("INSERT statement:")
    println(e.insertDataSQLStatement())
    println()
    println("UPDATE statement:")
    println(e.updateDataSQLStatement())
}

fun testDbDataCreateString() {
    val e = ExperimentTableData()

    val names = e.extractPropertyNames()
    for (name in names) {
        println(name)
    }
    println()
    val cs = e.createTableSQLStatement()
    println(cs)
}

fun testSimpleDb() {
    val p = Person(1, "manuel", age = 10)
    println(p.createTableSQLStatement())
    val c = City(1, "London", population = 1000)
    println(c.createTableSQLStatement())
    val db = Database.createSimpleDb(setOf(p, c), "TestSimpleDb")
    db.insertDbDataIntoTable(p)
    db.insertDbDataIntoTable(c)
    println(db.asString())
    println("done")
}

data class Person(
    var id: Int = 1,
    var name: String = "",
    var age: Int = 1
) : DbTableData("Persons", listOf("id"))

data class City(
    var id: Int = 1,
    var name: String = "",
    var population: Int = 1
) : DbTableData("Cities", listOf("id"))

fun testSQLiteDb(){
    val td = setOf(Person(), City())
    val db = SQLiteDb(td, "TestSQLiteDb")
    db.userDefinedTables.forEach(::println)
    val p = Person(1, "manuel", age = 10)
    val c = City(1, "London", population = 1000)
    db.insertDbDataIntoTable(p)
    db.insertDbDataIntoTable(c)
    db.printAllTablesAsText()
    println()
    db.tableNames(null).forEach(::println)
}

fun testDerbyDb(){
    val td = setOf(Person(), City())
    val db = DerbyDb(td, "TestDerbyDb")
    db.userDefinedTables.forEach(::println)
    val p = Person(1, "manuel", age = 10)
    val c = City(1, "London", population = 1000)
    db.insertDbDataIntoTable(p)
    db.insertDbDataIntoTable(c)
    db.printAllTablesAsText()
    println()
    db.tableNames("APP").forEach(::println)
}

fun testDuckDb(){
    val td = setOf(Person(), City())
    val db = DuckDb(td, "TestDuckDb")
    println(db)
    println()
    db.userDefinedTables.forEach(::println)
    println()
    val p = Person(1, "manuel", age = 10)
    val c = City(1, "London", population = 1000)
    db.insertDbDataIntoTable(p)
    db.insertDbDataIntoTable(c)
    val p2 = Person(2, "amy", age = 10)
    val p3 = Person(3, "joe", age = 10)
    db.appendDbDataToTable(listOf(p2,p3), "Persons")
    db.printAllTablesAsText()
    println()
    db.tableNames("main").forEach(::println)
    println()

    val exportPath = db.exportAsLoadableCSV("testDuckDbExport")
    val nDb = DuckDb.importFromLoadableCSV(exportPath, "ImportedDuckDb")
    println(nDb)
    nDb.printAllTablesAsText()

}

fun testDuckDbParquetFiles(){
    val td = setOf(Person(), City())
    val db = DuckDb(td, "TestDuckDb")
    println(db)
    println()
    db.userDefinedTables.forEach(::println)
    println()
    val p = Person(1, "manuel", age = 10)
    val c = City(1, "London", population = 1000)
    db.insertDbDataIntoTable(p)
    db.insertDbDataIntoTable(c)
    db.printAllTablesAsText()
    println()
    db.tableNames("main").forEach(::println)
    println()

    val exportPath = db.exportAsLoadableParquetFiles("testDuckDbExport")
//    val nDb = DuckDb.importFromLoadableCSV(exportPath, "ImportedDuckDb")
//    println(nDb)
 //   nDb.printAllTablesAsText()

}
fun testConvertToDuckDb(){
    val sPath = KSL.dbDir.resolve("someDB.db")
    val ds = SQLiteDb.createDataSource(sPath)
    val database = Database(ds, "someDB.db")
    database.executeCommand("drop table if exists person")
    database.executeCommand("create table person (id integer, name string)")
    println(database)
    database.executeCommand("insert into person values(1, 'PersonA')")
    database.executeCommand("insert into person values(2, 'PersonB')")
    database.printTableAsText(tableName = "person")

    val ddb = DuckDb.convertFromSQLiteToDuckDb(sPath, "someDBAsDuck")
    println(ddb)
    ddb.printAllTablesAsText()
}

fun testPostgres(){
//    val ds = PostgresDb.createDataSourceWithLocalHost("rossetti")
    val dbName = "test"
    val user = "test"
    val pw = "test"
    val dataSource: DataSource = PostgresDb.createDataSourceWithLocalHost(dbName, user, pw)
    // make the database
    val db = Database(dataSource, dbName)
    // builder the creation task
    val pathToCreationScript: Path = pathToDbExamples.resolve("SPDatabase_Postgres.sql")
    val task = db.create().withCreationScript(pathToCreationScript).execute()
//    System.out.println(task)
//    task.creationScriptCommands.forEach(System.out::println)
//    db.printTableAsText("s")
    db.userDefinedTables.forEach(::println)
    println()
    db.tableNames("public").forEach(::println)
}

