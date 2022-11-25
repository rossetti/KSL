package examplepkg

import ksl.utilities.io.dbutil.DatabaseFactory
import org.ktorm.dsl.isNotNull
import org.ktorm.schema.Table
import org.ktorm.schema.*

class SPDatabase(dbName: String) {

    private val myDb = DatabaseFactory.createEmbeddedDerbyDatabase(dbName)

    object Suppliers : Table<Nothing>("Supplier"){
        val snum = int("snum").primaryKey()
        val sname = varchar("sname").isNotNull()
        val status = int("status").isNotNull()
        val city = varchar("city").isNotNull()
    }

    object Parts : Table<Nothing>("Part"){
        val pnum = int("pnum").primaryKey()
        val pname = varchar("pname").isNotNull()
        val color = varchar("color").isNotNull()
        val weight = decimal("weight").isNotNull()
        val city = varchar("city").isNotNull()
    }

    object Shipments : Table<Nothing>("Shipment"){
        val snum = int("snum_fk").primaryKey()
        val pnum = int("pnum_fk").primaryKey()
        val qty = int("qty").isNotNull()
    }

}