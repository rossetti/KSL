package ksl.utilities.io

import ksl.utilities.io.ExcelUtil.logger
import ksl.utilities.io.dbutil.DatabaseIfc
import ksl.utilities.io.dbutil.ResultSetRowIterator
import org.dhatim.fastexcel.Workbook
import java.io.FileOutputStream
import java.nio.file.Path
import java.sql.ResultSet

object ExcelUtil2 {

    /** Writes each table in the list to an Excel workbook with each table being placed
     *  in a new sheet with the sheet name equal to the name of the table. The column names
     *  for each table are written as the first row of each sheet.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the name of the schema containing the tables or null
     * @param tableNames the names of the tables to write to a workbook
     * @param path the path to the workbook
     * @param db the database containing the tables
     */
    fun exportTablesToExcel(db: DatabaseIfc, path: Path, tableNames: List<String>, schemaName: String?) {
        FileOutputStream(path.toFile()).use {
            logger.info { "Opened workbook $path for writing database ${db.label} output" }
            DatabaseIfc.logger.info { "Writing database ${db.label} to workbook at $path" }
            val wb = Workbook(it, "KSL", "1.0")
            for (tableName in tableNames) {
                // get result set
                val rs = db.selectAllIntoOpenResultSet(tableName, schemaName)
                if (rs != null) {
                    // write result set to workbook
                    exportAsWorkSheet(rs, wb, tableName)
                    // close result set
                    rs.close()
                }
            }
            wb.finish()
            wb.close()
            logger.info { "Closed workbook $path after writing database ${db.label} output" }
            DatabaseIfc.logger.info { "Completed database ${db.label} export to workbook at $path" }
        }
    }

    /** Exports the data in the ResultSet to an Excel worksheet. The ResultSet is assumed to be forward
     * only and each row is processed until all rows are exported. The ResultSet is closed after
     * the processing.
     *
     * @param resultSet the result set to copy from
     * @param workbook the workbook to write into
     * @param sheetName the name of the sheet in the workbook to hold the results set values
     * @param writeHeader whether to write a header of the column names into the sheet. The default is true
     */
    fun exportAsWorkSheet(resultSet: ResultSet, workbook: Workbook, sheetName: String, writeHeader: Boolean = true) {
        require(!resultSet.isClosed) { "The supplied ResultSet is closed when trying to write workbook $sheetName " }
        // write the header
        val sheet = workbook.newWorksheet(sheetName)
        var rowCnt = 0
        val names = DatabaseIfc.columnNames(resultSet)
        if (writeHeader) {
            for (col in names.indices) {
                sheet.value(rowCnt, col, names[col])
                sheet.width(col, minOf(names[col].length, 256).toDouble())
//                sheet.setColumnWidth(col, ((names[col].length + 2) * 256))
            }
            rowCnt++
        }
        // write all the rows
        val iterator = ResultSetRowIterator(resultSet)
        while (iterator.hasNext()) {
            val list = iterator.next()
//            val row = sheet.createRow(rowCnt)
//            for (col in list.indices) {
//                ExcelUtil.writeCell(row.createCell(col), list[col])
//            }
            rowCnt++
            sheet.flush()
        }
        resultSet.close()
        sheet.flush()
        sheet.finish()
     //   sheet.close()
        DatabaseIfc.logger.info { "Completed exporting ResultSet to Excel worksheet $sheetName" }
        TODO("Not implemented yet")
    }

}