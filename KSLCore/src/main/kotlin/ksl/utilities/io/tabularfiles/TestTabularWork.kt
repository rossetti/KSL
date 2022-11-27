package ksl.utilities.io.tabularfiles

import ksl.utilities.io.KSL
import ksl.utilities.io.dbutil.DatabaseIfc
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.random.rvariable.NormalRV
import java.io.IOException
import java.io.PrintWriter
import java.nio.file.Path

fun main() {

    // demonstrate writing a file
    writeFile()
    // demonstrate reading a file
    readFile()
}

private fun writeFile() {
    val path: Path = KSLDatabase.dbDir.resolve("demoFile")

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

private fun readFile() {
    val path: Path = KSLDatabase.dbDir.resolve("demoFile")
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
    //TODO data has quotes
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
