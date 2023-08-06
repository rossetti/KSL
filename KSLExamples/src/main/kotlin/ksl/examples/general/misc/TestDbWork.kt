package ksl.examples.general.misc

import ksl.utilities.io.dbutil.Database
import ksl.utilities.io.dbutil.DerbyDb

class TestDbWork {
}

fun main(){
    val ds = DerbyDb.createDataSource("TestSPDb", create = true)
    val db = Database(ds, "TestSPDb")

    val list = db.tableNames("SQLJ")
    println(list.toString())

    println(db)
}