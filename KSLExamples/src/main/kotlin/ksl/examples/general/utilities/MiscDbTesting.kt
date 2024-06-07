package ksl.examples.general.utilities

import ksl.utilities.io.dbutil.DatabaseIfc
import ksl.utilities.io.dbutil.DbTableData
import ksl.utilities.io.dbutil.ExperimentTableData
import ksl.utilities.io.dbutil.SimpleDb

fun main() {
 //   testDbDataCreateString()
    testSimpleDb()
}

fun testDbData(){
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

fun testDbDataCreateString(){
    val e = ExperimentTableData()

    val names = e.extractPropertyNames()
    for(name in names ){
        println(name)
    }
    println()
    val cs = e.createTableSQLStatement()
    println(cs)
}

fun testSimpleDb(){
    val p = Person(1, "manuel", age = 10)
    println(p.createTableSQLStatement())
    val c = City(1, "London", population = 1000)
    println(c.createTableSQLStatement())
    val db = SimpleDb(setOf(p, c), "TestSimpleDb")
    db.insertDbDataIntoTable(p)
    db.insertDbDataIntoTable(c)
    println(db.asString())
    println("done")
}

data class Person(
    var id: Int,
    var name: String,
    var age: Int
) : DbTableData("Persons", listOf("id"))

data class City(
    var id: Int,
    var name: String,
    var population: Int
) : DbTableData("Cities", listOf("id"))