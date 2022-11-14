package examplepkg

import ksl.utilities.dbutil.DatabaseFactory
import ksl.utilities.dbutil.Database

class TestDbWork {
}

fun main(){
    val ds = DatabaseFactory.createEmbeddedDerbyDataSource("TestSPDb", create = true)
    val db = Database(ds, "TestSPDb")

    val list = db.tableNames("SQLJ")
    println(list.toString())

    println(db)
}