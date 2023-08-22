package ksl.examples.general.utilities

import ksl.utilities.io.KSL
import ksl.utilities.io.dbutil.DatabaseIfc
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.TabularData
import ksl.utilities.io.tabularfiles.*
import ksl.utilities.random.rvariable.NormalRV
import java.io.IOException
import java.io.PrintWriter
import java.nio.file.Path

fun main() {

    // demonstrate writing a file
    writeFile()
    // demonstrate reading a file
    readFile()
    // demonstrate writing tabular data
    writeTabularDataV2()
}

/**
 *  Writes a tabular file with 5 columns. The first 3 columns are numeric data. The fourth
 *  column is text, and the fifth column is numeric.
 */
private fun writeFile() {
    val path: Path = KSL.outDir.resolve("demoFile")

    // configure the columns
    val columns: MutableMap<String, DataType> = TabularFile.columns(3, DataType.NUMERIC).toMutableMap()
    columns["c4"] = DataType.TEXT
    columns["c5"] = DataType.NUMERIC

    // make the file
    val tif = TabularOutputFile(columns, path)
    println(tif)

    // needed for some random data
    val n = NormalRV(10.0, 1.0)
    val k = 15
    // get a row
    val row: RowSetterIfc = tif.row()
    // write some data to each row
    println("Writing rows...")
    for (i in 1..k) {
        // reuse the same row, many times
        // can fill all numeric columns
        row.setNumeric(n.sample(5))
        // can set specific columns
        row.setText(3, "text data $i")
        // need to write the row to the buffer
        tif.writeRow(row)
    }
    // don't forget to flush the buffer
    tif.flushRows()
    println("Done writing rows!")
    println()
}

private fun writeTabularData(){
    val path: Path = KSL.outDir.resolve("TabularDataFile")
    data class SomeData(var city: String = "", var age: Double = 0.0): TabularData("SomeData")
    // use the data class to define the columns and their types
    val tof = TabularOutputFile(SomeData(), path)
    println(tof)

    // needed for some random data
    val n = NormalRV(10.0, 1.0)
    val k = 15
    // get a row
    val row: RowSetterIfc = tof.row()
    // write some data to each row
    println("Writing rows...")
    for (i in 1..k) {
        // reuse the same row, many times
        // can fill all numeric columns
        row.setNumeric(1, n.value)
        // can set specific columns
        row.setText(0, "city data $i")
        // need to write the row to the buffer
        tof.writeRow(row)
    }
    // don't forget to flush the buffer
    tof.flushRows()
    println("Done writing rows!")
    println()

    val tif = TabularInputFile(path)
    println(tif)

    // TabularInputFile is Iterable and foreach construct works across rows
    println("Printing all rows from an iterator")
    for (row in tif.iterator()) {
        println(row)
    }
    println()
    println()
}

private fun writeTabularDataV2(){
    val path: Path = KSL.outDir.resolve("TabularDataFile")
    data class SomeData(var someText: String = "", var someData: Double = 0.0): TabularData("SomeData")
    val rowData = SomeData()
    // use the data class instance to define the columns and their types
    val tof = TabularOutputFile(rowData, path)
    println(tof)

    // needed for some random data
    val n = NormalRV(10.0, 1.0)
    val k = 15
    // write some data to each row
    println("Writing rows...")
    for (i in 1..k) {
        // reuse the same row, many times
        // can fill all numeric columns
        rowData.someData = n.value
        rowData.someText = "text data $i"
        // need to write the row to the buffer
        tof.writeRow(rowData)
    }
    // don't forget to flush the buffer
    tof.flushRows()
    println("Done writing rows!")
    println()

    val tif = TabularInputFile(path)
    println(tif)

    // TabularInputFile is Iterable and foreach construct works across rows
    println("Printing all rows from an iterator using data class")
    for (row in tif.iterator()) {
        rowData.setPropertyValues(row)
        println(rowData)
    }
    println()
    println()
}

private fun readFile() {
    val path: Path = KSL.outDir.resolve("demoFile")
    val tif = TabularInputFile(path)
    println(tif)

    // TabularInputFile is Iterable and foreach construct works across rows
    println("Printing all rows from an iterator")
    for (row in tif.iterator()) {
        println(row)
    }
    println()
    println()

    // You can fetch rows as a list
    println("Printing a subset of rows")
    val rows: List<RowGetterIfc> = tif.fetchRows(1, 5)
    for (row in rows) {
        println(row)
    }
    println()
    println()

    // You can iterate starting at any row
    println("Print starting at row 9")
    val iterator: TabularInputFile.RowIterator = tif.iterator(9)
    while (iterator.hasNext()) {
        println(iterator.next())
    }
    println()
    println()

    // You can grab various columns as arrays
    println("Printing column 0")
    val numericColumn: DoubleArray = tif.fetchNumericColumn(0, 10, true)
    for (v in numericColumn) {
        println(v)
    }

    // You can write the data to an Excel workbook
    try {
        tif.exportToExcelWorkbook("demoData.xlsx", KSL.excelDir)
    } catch (e: IOException) {
        e.printStackTrace()
    }

    // You can pretty print rows of the data
    tif.printAsText(1, 5)

    // You can write the data to CSV
    val printWriter: PrintWriter = KSL.createPrintWriter("data.csv")
    tif.exportToCSV(printWriter, true)
    printWriter.close()

    // You can copy the file to a SQLite database
    try {
        val database: DatabaseIfc = tif.asDatabase()
    } catch (e: IOException) {
        e.printStackTrace()
    }
    println()
    println("Printing a data frame version")
    val df = tif.asDataFrame()
    println(df)
    println("Done with demo example.")

    tif.close()
}
