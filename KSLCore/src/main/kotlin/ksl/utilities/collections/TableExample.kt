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

package ksl.utilities.collections

/**
 * This file provides examples of how to use the Table implementation.
 * It can be run as a standalone program to verify the functionality.
 */
fun main() {
    println("Table Example")
    println("=============")
    
    // Create a new empty table
    val table = HashBasedTable<String, String, Int>()
    println("Created empty table: isEmpty=${table.isEmpty()}, size=${table.size}")
    
    // Add some entries
    table.put("r1", "c1", 11)
    table.put("r1", "c2", 12)
    table.put("r2", "c1", 21)
    table.put("r2", "c2", 22)
    println("After adding entries: isEmpty=${table.isEmpty()}, size=${table.size}")
    
    // Get values
    println("Value at (r1, c1): ${table.get("r1", "c1")}")
    println("Value at (r2, c2): ${table.get("r2", "c2")}")
    println("Value at (r3, c3): ${table.get("r3", "c3")}")  // Should be null
    
    // Check contains
    println("Contains (r1, c1): ${table.contains("r1", "c1")}")
    println("Contains (r3, c3): ${table.contains("r3", "c3")}")
    println("Contains row r1: ${table.containsRow("r1")}")
    println("Contains column c2: ${table.containsColumn("c2")}")
    println("Contains value 21: ${table.containsValue(21)}")
    println("Contains value 99: ${table.containsValue(99)}")
    
    // Get row and column views
    val row1 = table.row("r1")
    println("Row r1: $row1")
    
    val col1 = table.column("c1")
    println("Column c1: $col1")
    
    // Get row and column key sets
    println("Row keys: ${table.rowKeySet}")
    println("Column keys: ${table.columnKeySet}")
    
    // Get all values
    println("All values: ${table.values}")
    
    // Iterate over all cells
    println("All cells:")
    for (cell in table.cellSet) {
        println("  (${cell.rowKey}, ${cell.columnKey}) -> ${cell.value}")
    }
    
    // Remove an entry
    val removed = table.remove("r1", "c1")
    println("Removed value at (r1, c1): $removed")
    println("After removal: size=${table.size}, contains (r1, c1): ${table.contains("r1", "c1")}")
    
    // Remove a row
    val rowRemoved = table.removeRow("r2")
    println("Removed row r2: $rowRemoved")
    println("After row removal: size=${table.size}, contains row r2: ${table.containsRow("r2")}")
    
    // Create a new table from an existing one
    val table2 = HashBasedTable.create(table)
    println("Created table2 from table: size=${table2.size}")
    
    // Clear the table
    table.clear()
    println("After clearing table: isEmpty=${table.isEmpty()}, size=${table.size}")
    
    // Row and column maps
    val table3 = HashBasedTable<String, String, Int>()
    table3.put("r1", "c1", 11)
    table3.put("r1", "c2", 12)
    table3.put("r2", "c1", 21)
    
    val rowMap = table3.rowMap
    println("Row map: $rowMap")
    
    val columnMap = table3.columnMap
    println("Column map: $columnMap")
    
    println("\nTable implementation test completed successfully!")
}