/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.utilities.io.dbutil

import ksl.utilities.io.KSL
import java.nio.file.Path

/**
 *  The purpose of this class is to allow the creation of a quick and dirty database solution
 *  based on the DbTableData data classes.  By defining data classes that are subclasses of
 *  DbTableData, a CREATE TABLE specification can be obtained and the database created.
 *  Then the database can be used to insert data from instances of
 *  the DbTableData subclasses.
 *  
 *  @param tableDefinitions an example set of table definitions based on DbTableData specifications
 *  @param db the underlying database
 */
class SimpleDb(
    tableDefinitions: Set<DbTableData>,
    private val db: Database
) : DatabaseIfc by db {

    /** This constructs a SQLite database on disk.
     * The database will be empty.
     *
     * @param tableDefinitions an example set of table definitions based on DbTableData specifications
     * @param dbName the name of the database
     * @param dbDirectory the directory containing the database. By default, KSL.dbDir.
     * @return an empty embedded SQLite database
     */
    constructor(tableDefinitions: Set<DbTableData>, dbName: String, dbDirectory: Path = KSL.dbDir) : this(
        tableDefinitions, SQLiteDb.createDatabase(dbName, dbDirectory))

    init {
        for (tableData in tableDefinitions) {
            val worked = executeCommand(tableData.createTableSQLStatement())
            if (worked){
                DatabaseIfc.logger.info { "SimpleDb($label): table ${tableData.tableName} has been created." }
            } else {
                DatabaseIfc.logger.info { "SimpleDb($label): table ${tableData.tableName} was not created." }
            }
        }
    }
}