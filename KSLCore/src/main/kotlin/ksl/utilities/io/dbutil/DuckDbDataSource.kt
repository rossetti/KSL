package ksl.utilities.io.dbutil

import ksl.utilities.io.KSL
import java.nio.file.Path
import java.util.Properties

class DuckDbDataSource(
    pathToFile: Path,
    properties: Properties = Properties()
) : BaseDataSource("jdbc:duckdb:${pathToFile}", properties) {
}

fun main() {

}

fun testDuckDbCon(){
    val path = KSL.dbDir.resolve("TestDuckDb.db")
    val ds = DuckDbDataSource(path)
    val con = ds.connection
    con.close()
}