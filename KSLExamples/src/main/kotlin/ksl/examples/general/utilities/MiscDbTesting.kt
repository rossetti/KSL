package ksl.examples.general.utilities

import ksl.utilities.io.dbutil.*

fun main() {
    //   testDbDataCreateString()
   // testSimpleDb()
//    testDerbyDb()
    testDuckDb()
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

fun testDerbyDb(){
    val td = setOf(Person(), City())
    val db = DerbyDb(td, "TestDerbyDb")
    db.userDefinedTables.forEach(::println)
    val p = Person(1, "manuel", age = 10)
    val c = City(1, "London", population = 1000)
    db.insertDbDataIntoTable(p)
    db.insertDbDataIntoTable(c)
    db.printAllTablesAsText()
}

fun testDuckDb(){
    val td = setOf(Person(), City())
    val db = DuckDb(td, "TestDuckDb")
    db.userDefinedTables.forEach(::println)
    val p = Person(1, "manuel", age = 10)
    val c = City(1, "London", population = 1000)
    db.insertDbDataIntoTable(p)
    db.insertDbDataIntoTable(c)
    db.printAllTablesAsText()
}

