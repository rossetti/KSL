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

package ksl.utilities.io

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.utilities.io.dbutil.ColumnMetaData
import ksl.utilities.io.dbutil.DatabaseIfc
import ksl.utilities.io.dbutil.ResultSetRowIterator
import org.apache.commons.csv.CSVFormat
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.Worksheet
import org.dhatim.fastexcel.reader.Cell
import org.dhatim.fastexcel.reader.CellType
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.dhatim.fastexcel.reader.Row
import org.dhatim.fastexcel.reader.Sheet
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.io.PrintWriter
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Time
import java.sql.Timestamp
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ExcelUtil {

    val logger: KLogger = KotlinLogging.logger {}

    const val DEFAULT_MAX_CHAR_IN_CELL: Int = 512
    const val APP_NAME: String = "KSL"
    const val APP_VERSION: String = "1.0"

    /** Excel's hard limit on worksheet-name length. */
    const val MAX_SHEET_NAME_LENGTH: Int = 31

    val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    private val INVALID_SHEET_CHARS = charArrayOf('\\', '/', '?', '*', '[', ']', ':')

    /**
     * Classification of a cell's data format with respect to date vs. time
     * components. Used by both [readCellAsObject] (to choose the right
     * java.sql type) and [readCellAsString] (to format consistently).
     */
    private data class DateFormatKind(val hasDate: Boolean, val hasTime: Boolean) {
        val isAnyDate: Boolean get() = hasDate || hasTime
    }

    /**
     * Replacement for POI's WorkbookUtil.createSafeSheetName.
     * Replaces invalid characters with spaces and truncates to 31 chars.
     */
    fun createSafeSheetName(name: String): String {
        var safe = name
        for (c in INVALID_SHEET_CHARS) safe = safe.replace(c, ' ')
        safe = safe.trim()
        if (safe.length > MAX_SHEET_NAME_LENGTH) safe = safe.substring(0, MAX_SHEET_NAME_LENGTH)
        if (safe.isEmpty()) safe = "Sheet"
        return safe
    }

    /**
     * Creates a new worksheet with a safe name. fastexcel does not expose an
     * "already exists" check, so callers needing collision-free names should
     * track the names they have used and pass a unique value.
     */
    fun createSheet(workbook: Workbook, sheetName: String): Worksheet {
        val safe = createSafeSheetName(sheetName)
        val sheet = workbook.newWorksheet(safe)
        logger.info { "Created new sheet $safe in workbook" }
        return sheet
    }

    /**
     * Writes a value to (row, col) of the worksheet. Date/time types receive
     * an Excel format style. fastexcel deduplicates styles internally, so the
     * per-cell style call is cheap (unlike POI).
     */
    fun writeCell(ws: Worksheet, row: Int, col: Int, value: Any?) {
        when (value) {
            null -> { /* nothing to write */ }
            is String       -> ws.value(row, col, value.trim())
            is Boolean      -> ws.value(row, col, value)
            is Int          -> ws.value(row, col, value)
            is Long         -> ws.value(row, col, value)
            is Short        -> ws.value(row, col, value.toInt())
            is Float        -> ws.value(row, col, value.toDouble())
            is Double       -> ws.value(row, col, value)
            is BigDecimal   -> ws.value(row, col, value)
            is java.sql.Date -> {
                ws.value(row, col, value.toLocalDate())
                ws.style(row, col).format("m/d/yy").set()
            }
            is Time -> {
                // fastexcel has no java.sql.Time overload, so we anchor the
                // time at a modern date and rely on the reader/import path to
                // strip the date component. Pre-1900 anchors don't work:
                // fastexcel-writer encodes any LocalDateTime before
                // 1900-01-01 as the integer day number with the time fraction
                // dropped, which would lose the time value. The display style
                // "h:mm:ss AM/PM" hides the date portion in Excel.
                val ldt = value.toLocalTime().atDate(LocalDate.of(1900, 1, 1))
                ws.value(row, col, ldt)
                ws.style(row, col).format("h:mm:ss AM/PM").set()
            }
            is Timestamp -> {
                ws.value(row, col, value.toLocalDateTime())
                ws.style(row, col).format("yyyy-MM-dd HH:mm:ss").set()
            }
            is LocalDateTime -> {
                ws.value(row, col, value)
                ws.style(row, col).format("yyyy-MM-dd HH:mm:ss").set()
            }
            is LocalDate -> {
                ws.value(row, col, value)
                ws.style(row, col).format("yyyy-MM-dd").set()
            }
            is java.util.Date -> {
                ws.value(row, col, value)
                ws.style(row, col).format("yyyy-MM-dd HH:mm:ss").set()
            }
            else -> {
                logger.error { "Could not cast type ${value.javaClass.name} to Excel type." }
                throw ClassCastException("Could not cast database type to Excel type: ${value.javaClass.name}")
            }
        }
    }

    /**
     * Opens an .xlsx workbook for reading. Returns null on missing file or read error.
     * Caller is responsible for closing the returned ReadableWorkbook.
     */
    fun openReadableWorkbook(pathToWorkbook: Path): ReadableWorkbook? {
        val file = pathToWorkbook.toFile()
        if (!file.exists()) {
            logger.warn { "The file at $pathToWorkbook does not exist" }
            return null
        }
        return try {
            val wb = ReadableWorkbook(file)
            logger.info { "Opened workbook for reading only at: $pathToWorkbook" }
            wb
        } catch (e: IOException) {
            logger.error { "There was an IO error when trying to open the workbook at: $pathToWorkbook" }
            null
        }
    }

    /**
     * Reads an entire sheet as a list of object lists.
     * fastexcel streams rows; the stream is closed when this returns.
     */
    fun readSheetAsObjects(
        sheet: Sheet,
        numColumns: Int = numberColumnsForCSVHeader(sheet),
        skipFirstRow: Boolean = false
    ): List<List<Any?>> {
        val list = mutableListOf<List<Any?>>()
        sheet.openStream().use { stream ->
            val iter = stream.iterator()
            if (skipFirstRow && iter.hasNext()) iter.next()
            while (iter.hasNext()) {
                list.add(readRowAsObjectList(iter.next(), numColumns))
            }
        }
        return list
    }

    /**
     * Reads a row into a fixed-length list, padding missing cells with null.
     * fastexcel's Row.getCell throws on out-of-bounds indices (POI returned
     * null), so we bounds-check before reading.
     */
    fun readRowAsObjectList(row: Row, numColumns: Int = row.cellCount): List<Any?> {
        val list = mutableListOf<Any?>()
        val rowCells = row.cellCount
        for (i in 0 until numColumns) {
            val cell: Cell? = if (i < rowCells) row.getCell(i) else null
            list.add(if (cell == null) null else readCellAsObject(cell))
        }
        return list
    }

    /**
     * Translates a fastexcel cell into a "best fit" Java object.
     *
     *  - String/Boolean cells map to String/Boolean.
     *  - Numeric cells with a date format map to a java.sql.* type chosen by
     *    inspecting the format string: date-and-time → [Timestamp],
     *    date-only → [java.sql.Date], time-only → [Time]. Returning java.sql
     *    types keeps the DB round trip (export → re-import via
     *    PreparedStatement.setObject) portable across JDBC drivers and strips
     *    the 1899-12-30 anchor used when writing TIME values.
     *  - Other numeric cells map to BigDecimal (fastexcel's native numeric
     *    representation).
     */
    fun readCellAsObject(cell: Cell): Any? {
        return when (cell.type) {
            CellType.STRING -> cell.asString().trim()
            CellType.NUMBER -> {
                val kind = dateFormatKind(cell)
                val ldt: LocalDateTime? = if (kind.isAnyDate) cell.asDate() else null
                when {
                    kind.hasDate && kind.hasTime -> Timestamp.valueOf(ldt)
                    kind.hasDate                 -> java.sql.Date.valueOf(ldt!!.toLocalDate())
                    kind.hasTime                 -> Time.valueOf(ldt!!.toLocalTime())
                    else                         -> cell.asNumber()
                }
            }
            CellType.BOOLEAN -> cell.asBoolean()
            CellType.FORMULA -> cell.formula
            CellType.EMPTY, CellType.ERROR -> null
            else -> null
        }
    }

    /**
     * Translates a fastexcel cell into a value compatible with
     * PreparedStatement.setObject(idx, value) for a column whose JDBC type
     * is [jdbcType] (a constant from [java.sql.Types]).
     *
     * This is the path used by the database-import flow. Going through
     * the target column's JDBC type — rather than through the cell's data
     * format string — is necessary because fastexcel-writer's format
     * styles do not round trip through fastexcel-reader (the reader exposes
     * neither dataFormatId nor dataFormatString for cells styled via the
     * writer, as of fastexcel 0.18.4). For workbooks produced by other
     * tools whose format info survives, [readCellAsObject] is sufficient.
     */
    fun readCellForJdbcType(cell: Cell?, jdbcType: Int): Any? {
        if (cell == null) return null
        if (cell.type == CellType.EMPTY || cell.type == CellType.ERROR) return null
        return when (jdbcType) {
            Types.BIT, Types.BOOLEAN -> when (cell.type) {
                CellType.BOOLEAN -> cell.asBoolean()
                CellType.NUMBER  -> cell.asNumber().signum() != 0
                CellType.STRING  -> cell.asString().trim().equals("true", ignoreCase = true)
                else             -> null
            }
            Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
            Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR -> when (cell.type) {
                CellType.STRING  -> cell.asString().trim()
                CellType.NUMBER  -> cell.asNumber().toPlainString()
                CellType.BOOLEAN -> cell.asBoolean().toString()
                CellType.FORMULA -> cell.formula
                else             -> null
            }
            Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
            Types.FLOAT, Types.REAL, Types.DOUBLE,
            Types.NUMERIC, Types.DECIMAL -> when (cell.type) {
                CellType.NUMBER  -> cell.asNumber()
                CellType.STRING  -> runCatching { BigDecimal(cell.asString().trim()) }.getOrNull()
                CellType.BOOLEAN -> if (cell.asBoolean()) BigDecimal.ONE else BigDecimal.ZERO
                else             -> null
            }
            Types.DATE -> when (cell.type) {
                CellType.NUMBER -> java.sql.Date.valueOf(cell.asDate().toLocalDate())
                CellType.STRING -> runCatching { java.sql.Date.valueOf(cell.asString().trim()) }.getOrNull()
                else            -> null
            }
            Types.TIME, Types.TIME_WITH_TIMEZONE -> when (cell.type) {
                CellType.NUMBER -> Time.valueOf(cell.asDate().toLocalTime())
                CellType.STRING -> runCatching { Time.valueOf(cell.asString().trim()) }.getOrNull()
                else            -> null
            }
            Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> when (cell.type) {
                CellType.NUMBER -> Timestamp.valueOf(cell.asDate())
                CellType.STRING -> runCatching { Timestamp.valueOf(cell.asString().trim()) }.getOrNull()
                else            -> null
            }
            else -> readCellAsObject(cell)
        }
    }

    /**
     * Reads a row coerced to the supplied per-column JDBC types. Cells beyond
     * the row's [Row.cellCount] are read as null.
     */
    fun readRowForJdbcTypes(row: Row, jdbcTypes: List<Int>): List<Any?> {
        val list = mutableListOf<Any?>()
        val rowCells = row.cellCount
        for (i in jdbcTypes.indices) {
            val cell: Cell? = if (i < rowCells) row.getCell(i) else null
            list.add(readCellForJdbcType(cell, jdbcTypes[i]))
        }
        return list
    }

    /**
     * Translates a fastexcel cell into a String.
     */
    fun readCellAsString(cell: Cell): String {
        return when (cell.type) {
            CellType.STRING -> cell.asString()
            CellType.NUMBER -> {
                if (isDateFormatted(cell)) {
                    DATE_TIME_FORMATTER.format(
                        cell.asDate().atZone(ZoneId.systemDefault()).toInstant()
                    )
                } else {
                    cell.asNumber().toString()
                }
            }
            CellType.BOOLEAN -> cell.asBoolean().toString()
            CellType.FORMULA -> cell.formula
            else -> ""
        }
    }

    /**
     * Detects whether a numeric cell carries a date/time number format.
     * fastexcel exposes the raw format string via Cell.dataFormatString.
     */
    private fun isDateFormatted(cell: Cell): Boolean = dateFormatKind(cell).isAnyDate

    /**
     * Inspects a cell's format string and classifies it as containing a date
     * component, a time component, both, or neither. Quoted literals and
     * escaped characters are stripped so that a literal 'd' inside text
     * doesn't false-positive.
     */
    private fun dateFormatKind(cell: Cell): DateFormatKind {
        if (cell.type != CellType.NUMBER) return DateFormatKind(false, false)
        val fmt = cell.dataFormatString ?: return DateFormatKind(false, false)
        val stripped = fmt.replace(Regex("\"[^\"]*\""), "")
            .replace(Regex("\\\\."), "")
        val hasDate = stripped.any { it in "yMd" }
        val hasTime = stripped.any { it in "hHmsS" }
        return DateFormatKind(hasDate, hasTime)
    }

    fun readRowAsStringList(row: Row, numCol: Int, maxChar: Int = DEFAULT_MAX_CHAR_IN_CELL): List<String?> {
        require(numCol > 0) { "The number of columns must be >= 1" }
        require(maxChar > 0) { "The maximum number of characters must be >= 1" }
        val list = mutableListOf<String?>()
        val rowCells = row.cellCount
        for (i in 0 until numCol) {
            val cell: Cell? = if (i < rowCells) row.getCell(i) else null
            var s: String? = null
            if (cell != null) {
                s = readCellAsString(cell)
                if (s.length > maxChar) {
                    s = s.substring(0, maxChar - 1)
                    logger.warn { "The cell ${cell.rawValue} was truncated to $maxChar characters" }
                }
            }
            list.add(s)
        }
        return list
    }

    fun readRowAsStringArray(row: Row, numCol: Int, maxChar: Int = DEFAULT_MAX_CHAR_IN_CELL): Array<String?> {
        return readRowAsStringList(row, numCol, maxChar).toTypedArray()
    }

    /**
     * fastexcel has no random row access, so we materialize the sheet to walk
     * up from the bottom. Acceptable since this is used only for sizing.
     */
    fun columnSize(sheet: Sheet, columnIndex: Int): Int {
        val rows = sheet.read()
        var lastRow = rows.size - 1
        while (lastRow >= 0 && isCellEmpty(rows[lastRow].getCell(columnIndex))) {
            lastRow--
        }
        return lastRow + 1
    }

    fun isCellEmpty(cell: Cell?): Boolean {
        if (cell == null) return true
        return when (cell.type) {
            CellType.EMPTY -> true
            CellType.STRING -> cell.asString().isEmpty()
            else -> false
        }
    }

    fun writeSheetToCSV(
        sheet: Sheet,
        numCol: Int = numberColumnsForCSVHeader(sheet),
        skipFirstRow: Boolean = false,
        pathToCSV: Path = KSL.outDir.resolve("${sheet.name}.csv"),
        maxChar: Int = DEFAULT_MAX_CHAR_IN_CELL
    ) {
        require(numCol > 0) { "The number of columns must be >= 1" }
        require(maxChar > 0) { "The maximum number of characters must be >= 1" }
        FileWriter(pathToCSV.toFile()).use { fileWriter ->
            CSVFormat.DEFAULT.builder().build().print(fileWriter).use { writer ->
                sheet.openStream().use { stream ->
                    val iter = stream.iterator()
                    if (skipFirstRow && iter.hasNext()) iter.next()
                    while (iter.hasNext()) {
                        val row = iter.next()
                        val strings = readRowAsStringArray(row, numCol, maxChar)
                        writer.printRecord(*strings)
                    }
                }
            }
        }
    }

    /**
     * Opens a one-row stream just to size the header.
     */
    fun numberColumnsForCSVHeader(sheet: Sheet): Int {
        sheet.openStream().use { stream ->
            val first = stream.findFirst()
            return if (first.isPresent) first.get().cellCount else 0
        }
    }

    fun numberColumns(row: Row): Int = row.cellCount

    /**
     * Writes a Map<String,Double> to a single-sheet workbook.
     * NaN / +Infinity / -Infinity are written as the corresponding strings.
     */
    fun writeToExcel(
        map: Map<String, Double>,
        sheetName: String,
        wbName: String = sheetName,
        wbDirectory: Path = KSL.excelDir,
        header: Boolean = false
    ) {
        val wbn = if (!wbName.endsWith(".xlsx")) "$wbName.xlsx" else wbName
        val path = wbDirectory.resolve(wbn)
        FileOutputStream(path.toFile()).use { fos ->
            logger.info { "Opened workbook $path for writing map $sheetName to Excel" }
            Workbook(fos, APP_NAME, APP_VERSION).use { wb ->
                val ws = wb.newWorksheet(createSafeSheetName(sheetName))
                var rowCnt = 0
                if (header) {
                    ws.value(0, 0, "Element Name")
                    ws.value(0, 1, "Element Value")
                    rowCnt++
                }
                for ((n, v) in map) {
                    ws.value(rowCnt, 0, n)
                    when {
                        v.isNaN()                     -> ws.value(rowCnt, 1, "NaN")
                        v == Double.POSITIVE_INFINITY -> ws.value(rowCnt, 1, "+Infinity")
                        v == Double.NEGATIVE_INFINITY -> ws.value(rowCnt, 1, "-Infinity")
                        else                          -> ws.value(rowCnt, 1, v)
                    }
                    rowCnt++
                }
            }
            logger.info { "Closed workbook $path after writing map $sheetName to Excel" }
        }
    }

    /**
     * Inverse of writeToExcel(Map<String,Double>).
     */
    fun readToMap(
        sheetName: String,
        pathToWorkbook: Path = KSL.excelDir.resolve("${sheetName}.xlsx"),
        skipFirstRow: Boolean = false,
    ): Map<String, Double> {
        val workbook = openReadableWorkbook(pathToWorkbook)
            ?: throw IOException("There was a problem opening the workbook at $pathToWorkbook!")
        workbook.use { wb ->
            val sheet = wb.findSheet(sheetName).orElse(null)
            if (sheet == null) {
                logger.info { "No corresponding sheet named $sheetName in workbook $pathToWorkbook" }
                return emptyMap()
            }
            val map = mutableMapOf<String, Double>()
            sheet.openStream().use { stream ->
                val iter = stream.iterator()
                if (skipFirstRow && iter.hasNext()) iter.next()
                while (iter.hasNext()) {
                    val row = iter.next()
                    val rowData = readRowAsStringList(row, 2)
                    val key = rowData[0] ?: continue
                    val raw = rowData[1] ?: continue
                    map[key] = when (raw) {
                        "NaN"       -> Double.NaN
                        "+Infinity" -> Double.POSITIVE_INFINITY
                        "-Infinity" -> Double.NEGATIVE_INFINITY
                        else        -> raw.toDouble()
                    }
                }
            }
            return map
        }
    }

    /**
     * Streams a JDBC ResultSet to a worksheet. The ResultSet is closed when done.
     */
    fun exportAsWorkSheet(resultSet: ResultSet, ws: Worksheet, writeHeader: Boolean = true) {
        require(!resultSet.isClosed) { "The supplied ResultSet is closed when trying to write workbook ${ws.name}" }
        var rowCnt = 0
        val names = DatabaseIfc.columnNames(resultSet)
        if (writeHeader) {
            for (col in names.indices) {
                ws.value(0, col, names[col])
                // fastexcel width is in characters, not POI's 1/256ths.
                ws.width(col, (names[col].length + 2).toDouble())
            }
            rowCnt++
        }
        val iterator = ResultSetRowIterator(resultSet)
        while (iterator.hasNext()) {
            val list = iterator.next()
            for (col in list.indices) {
                writeCell(ws, rowCnt, col, list[col])
            }
            rowCnt++
        }
        resultSet.close()
        // finish() (not flush()) closes this worksheet's zip entry before the
        // caller opens the next sheet. fastexcel streams into a single OPC zip
        // that allows only one open entry at a time; flush() opens the entry
        // but never closes it, so a multi-sheet export corrupts entry ordering
        // and Workbook.close() throws "not current zip current". finish() is
        // idempotent, so the later Workbook.close() -> ws.finish() is a no-op.
        ws.finish()
        DatabaseIfc.logger.info { "Completed exporting ResultSet to Excel worksheet ${ws.name}" }
    }

    /**
     * Returns a sheet name derived from [rawName] that is safe (see
     * createSafeSheetName) and not already present in [usedNames], adding the
     * chosen name to [usedNames] before returning.
     *
     * On collision a "_n" counter is appended, but — unlike a naive
     * "${rawName}_n" then truncate — the base is first shortened so that
     * base + suffix still fits within Excel's 31-character limit. Appending the
     * suffix before truncation would discard it for names that already reach 31
     * characters, so two identifiers sharing a 31-character prefix would
     * collapse to the same value on every attempt and the loop would never
     * terminate. Reserving room for the suffix guarantees each attempt is
     * distinct, so the loop ends within usedNames.size + 1 iterations.
     */
    private fun uniqueSheetName(rawName: String, usedNames: MutableSet<String>): String {
        val base = createSafeSheetName(rawName)
        if (usedNames.add(base)) return base
        var i = 1
        while (true) {
            val suffix = "_$i"
            val room = (MAX_SHEET_NAME_LENGTH - suffix.length).coerceAtLeast(0)
            val head = if (base.length > room) base.substring(0, room) else base
            val candidate = head.trimEnd() + suffix
            if (usedNames.add(candidate)) return candidate
            i++
        }
    }

    /**
     * Writes the supplied tables of a database to one workbook, one sheet per table.
     */
    fun exportTablesToExcel(db: DatabaseIfc, path: Path, tableNames: List<String>, schemaName: String?) {
        FileOutputStream(path.toFile()).use { fos ->
            logger.info { "Opened workbook $path for writing database ${db.label} output" }
            DatabaseIfc.logger.info { "Writing database ${db.label} to workbook at $path" }
            Workbook(fos, APP_NAME, APP_VERSION).use { wb ->
                val usedNames = mutableSetOf<String>()
                for (tableName in tableNames) {
                    val rs = db.selectAllIntoOpenResultSet(tableName, schemaName) ?: continue
                    val safe = uniqueSheetName(tableName, usedNames)
                    val ws = wb.newWorksheet(safe)
                    exportAsWorkSheet(rs, ws)
                    rs.close()
                }
            }
            logger.info { "Closed workbook $path after writing database ${db.label} output" }
            DatabaseIfc.logger.info { "Completed database ${db.label} export to workbook at $path" }
        }
    }

    /**
     * Imports each named sheet of the workbook into the table of the same name.
     * Sheets are processed in the supplied order to satisfy referential constraints.
     */
    fun importWorkbookToSchema(
        db: DatabaseIfc,
        pathToWorkbook: Path,
        tableNames: List<String>,
        schemaName: String?,
        skipFirstRow: Boolean
    ) {
        val workbook = openReadableWorkbook(pathToWorkbook)
            ?: throw IOException("There was a problem opening the workbook at $pathToWorkbook!")
        workbook.use { wb ->
            DatabaseIfc.logger.info { "Writing workbook $pathToWorkbook to database ${db.label}" }
            for (tableName in tableNames) {
                val sheet = wb.findSheet(tableName).orElse(null)
                if (sheet == null) {
                    DatabaseIfc.logger.info { "Skipping table $tableName no corresponding sheet in workbook" }
                    continue
                }
                DatabaseIfc.logger.trace { "Processing the sheet for table $tableName." }
                val tblMetaData = db.tableMetaData(tableName, schemaName)
                val dirStr = pathToWorkbook.toString().substringBeforeLast(".")
                val pathToBadRows = Path.of(dirStr).resolve("${tableName}_MissingRows.txt")
                DatabaseIfc.logger.trace { "The file to hold bad data for table $tableName is $pathToBadRows" }
                val badRowsFile = KSLFileUtil.createPrintWriter(pathToBadRows)
                val numToSkip = if (skipFirstRow) 1 else 0
                val success = importSheetToTable(
                    db, sheet, tableName, tblMetaData, schemaName, numToSkip,
                    unCompatibleRows = badRowsFile
                )
                if (!success) {
                    DatabaseIfc.logger.info { "Unable to write sheet $tableName to database ${db.label}. See trace logs for details" }
                } else {
                    DatabaseIfc.logger.info { "Wrote sheet $tableName to database ${db.label}." }
                }
            }
            DatabaseIfc.logger.info { "Completed writing workbook $pathToWorkbook to database ${db.label}" }
        }
    }

    fun importSheetToTable(
        db: DatabaseIfc,
        sheet: Sheet,
        tableName: String,
        tblMetaData: List<ColumnMetaData>,
        schemaName: String?,
        numRowsToSkip: Int,
        rowBatchSize: Int = 100,
        unCompatibleRows: PrintWriter
    ): Boolean {
        val numColumns = tblMetaData.size
        val jdbcTypes = tblMetaData.map { it.type }
        return try {
            sheet.openStream().use { stream ->
                val iter = stream.iterator()
                for (i in 1..numRowsToSkip) {
                    if (iter.hasNext()) iter.next()
                }
                DatabaseIfc.logger.trace {
                    "Getting connection to import ${sheet.name} into table $tableName of schema $schemaName of database ${db.label}"
                }
                DatabaseIfc.logger.trace {
                    "Table $tableName to hold data for sheet ${sheet.name} has $numColumns columns to fill."
                }
                db.getConnection().use { con ->
                    con.autoCommit = false
                    val insertStatement = DatabaseIfc.makeInsertPreparedStatement(con, tableName, numColumns, schemaName)
                    var batchCnt = 0
                    var cntBad = 0
                    var rowCnt = 0
                    var cntGood = 0
                    while (iter.hasNext()) {
                        val row = iter.next()
                        val rowData = readRowForJdbcTypes(row, jdbcTypes)
                        rowCnt++
                        DatabaseIfc.logger.trace { "Read ${rowData.size} elements from sheet ${sheet.name}" }
                        DatabaseIfc.logger.trace { "Sheet Data: $rowData" }
                        val success = DatabaseIfc.addBatch(rowData, numColumns, insertStatement)
                        if (!success) {
                            DatabaseIfc.logger.trace { "Wrote row number ${row.rowNum} of sheet ${sheet.name} to bad data file" }
                            unCompatibleRows.println("Sheet: ${sheet.name} row: ${row.rowNum} not written: $rowData")
                            cntBad++
                        } else {
                            DatabaseIfc.logger.trace { "Inserted data into batch for insertion" }
                            batchCnt++
                            if (batchCnt.mod(rowBatchSize) == 0) {
                                val ni = insertStatement.executeBatch()
                                con.commit()
                                DatabaseIfc.logger.trace { "Wrote batch of size ${ni.size} to table $tableName" }
                                batchCnt = 0
                            }
                            cntGood++
                        }
                    }
                    if (batchCnt > 0) {
                        val ni = insertStatement.executeBatch()
                        con.commit()
                        DatabaseIfc.logger.trace { "Wrote batch of size ${ni.size} to table $tableName" }
                    }
                    DatabaseIfc.logger.info {
                        "Transferred $cntGood out of $rowCnt rows for ${sheet.name}. There were $cntBad incompatible rows written."
                    }
                }
            }
            true
        } catch (ex: SQLException) {
            DatabaseIfc.logger.error(ex) {
                "SQLException when importing ${sheet.name} into table $tableName of schema $schemaName of database ${db.label}"
            }
            false
        }
    }
}
