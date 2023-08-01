/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.examples.general

import ksl.utilities.io.KSL
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement


class TestDuckDb {
}

fun main() {
    val fn = KSL.dbDir.resolve("TestDuckDb").toString()
    val conn: Connection = DriverManager.getConnection("jdbc:duckdb:$fn")

    // create a table
    val stmt: Statement = conn.createStatement()
    stmt.execute("CREATE TABLE items (item VARCHAR, value DECIMAL(10,2), count INTEGER)")
    // insert two items into the table
    stmt.execute("INSERT INTO items VALUES ('jeans', 20.0, 1), ('hammer', 42.2, 2)")
    stmt.executeQuery("SELECT * FROM items").use { rs ->
        while (rs.next()) {
            println(rs.getString(1))
            println(rs.getInt(3))
        }
    }
}