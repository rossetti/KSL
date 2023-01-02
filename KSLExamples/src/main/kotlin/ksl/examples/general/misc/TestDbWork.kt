package ksl.examples.general.misc

import ksl.utilities.io.dbutil.DatabaseFactory
import ksl.utilities.io.dbutil.Database

class TestDbWork {
}

fun main(){
    val ds = DatabaseFactory.createEmbeddedDerbyDataSource("TestSPDb", create = true)
    val db = Database(ds, "TestSPDb")

    val list = db.tableNames("SQLJ")
    println(list.toString())

    println(db)
}