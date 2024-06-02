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

package ksl.examples.book.appendixD

import ksl.utilities.KSLArrays
import ksl.utilities.io.*
import ksl.utilities.io.dbutil.DatabaseIfc
import ksl.utilities.io.tabularfiles.*
import ksl.utilities.random.rvariable.NormalRV
import java.io.IOException
import java.io.PrintWriter
import java.nio.file.Path
import java.nio.file.Paths

/**
 *  This file provides some demonstration examples of KSL IO functionality.
 */
fun main() {
//    demoOutputDirectory()
 //   demoKSLClass()
//    demoCSVUtil()
//    val file =  KSLFileUtil.chooseFile()
//    println("The chosen file path was $file")
//    demoExcelMap()
    demoWritingTabularFile()
    demoReadingTabularFile()
}

/**
 *  This function illustrates how to create an OutputDirectory and
 *  use some of its functionality.
 */
fun demoOutputDirectory() {
    // get the working directory
    val path = Paths.get("").toAbsolutePath()
    println("Working Directory = $path")
    // creates a directory called TestOutputDir in the current working directory
    // Creates subdirectories: csvDir, dbDir, excelDir and file out.txt
    val outDir = OutputDirectory(path.resolve("TestOutputDir"))
    // write to the default file
    outDir.out.println("Use out property to write out text to a file.")
    // Creates a PrintWriter (and file) to write to within TestOutputDir
    val pw = outDir.createPrintWriter("PW_File.txt")
    pw.println("Hello, World")
    val subDir = outDir.createSubDirectory("SubDir")
    println(outDir)
}

/**
 *  This function illustrates how to use the singleton KSL class for
 *  creating directories, files, and for general purpose output.
 */
fun demoKSLClass() {
    // use KSL like you use OutputDirectory but with some added functionality
    // The PW_File.txt file with be within the kslOutput directory within the working directory
    val pw = KSL.createPrintWriter("PW_File.txt")
    pw.println("Hello, World!")
    // Creates subdirectory SubDir within the kslOutput directory
    KSL.createSubDirectory("SubDir")
    // use KSL.out to write to kslOutput.txt
    KSL.out.println("Information written into kslOutput.txt")
    println(KSL)
    // KSL also has logger. This logs to logs/ksl.log
    KSL.logger.info { "This is an informational log comment!" }
}

/**
 *  This class illustrate how to use the simplified CSV capability of the KSL.
 */
fun demoCSVUtil(){
    val n = NormalRV()
    val matrix = n.sampleAsColumns(sampleSize = 5, nCols = 4)
    for(i in matrix.indices){
        println(matrix[i].contentToString())
    }
    val h = listOf("col1", "col2", "col3", "col4")
    val p = KSL.csvDir.resolve("data.csv")
    CSVUtil.writeArrayToCSVFile(matrix, header = h.toMutableList(), applyQuotesToData = false, pathToFile = p)
    println()
    val dataAsList: List<Array<String>> = CSVUtil.readRows(p, skipLines = 1)
    val m = KSLArrays.parseTo2DArray(dataAsList)
    for(i in matrix.indices){
        println(m[i].contentToString())
    }
}

/**
 *  This class illustrates how to save a map to Excel and how to read back the
 *  values from the Excel file.
 */
fun demoExcelMap(){
    val map = mapOf(
        "first" to 1.0,
        "second" to 2.0,
        "third" to Double.POSITIVE_INFINITY,
        "fourth" to Double.NaN)
    ExcelUtil.writeToExcel(map, "TestExcelMap")
    val inMap = ExcelUtil.readToMap("TestExcelMap")
    for((key, value) in inMap){
        println("$key -> $value")
    }
}

/**
 *  Writes a tabular file with 5 columns. The first 3 columns are numeric data. The fourth
 *  column is text, and the fifth column is numeric.
 */
fun demoWritingTabularFile() {
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

/**
 *  This function illustrates how to read data from an existing tabular file.
 *  In addition, it illustrates how access rows and columns of the file,
 *  translate the file into a database form, and translate the file into
 *  a data frame form.
 */
fun demoReadingTabularFile() {
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
    DataFrameUtil.toTabularFile(df, "DataFrameVersion")
    tif.close()

    val fp = KSL.outDir.resolve("data.csv")
    println(fp)
    //TabularFile.createFromCSVFile(fp, tif.columnTypes, KSL.outDir.resolve("DataFromCSV"))
    TabularFile.createFromCSVFile(fp, tif.columnTypes)
}
