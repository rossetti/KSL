package ksl.utilities.io

import ksl.utilities.io.dbutil.DerbyDb
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Time
import java.sql.Timestamp

/**
 * Phase 4 — End-to-end DB round trip via [ExcelUtil].
 *
 * Creates a Derby table with one column of each SQL type KSL supports,
 * inserts a known row, exports the table to xlsx via
 * `ExcelUtil.exportTablesToExcel`, truncates the table, re-imports the
 * sheet via `ExcelUtil.importWorkbookToSchema`, then verifies that every
 * column survived the trip.
 *
 * Derby is used (rather than SQLite) because Derby honours declared
 * column types and returns the proper `java.sql.*` value classes from
 * `ResultSet.getObject`, which is what the export path consumes.
 */
class ExcelUtilDbRoundTripTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var db: ksl.utilities.io.dbutil.Database
    private val schema = "APP"
    private val table = "ROUNDTRIP"

    @BeforeEach
    fun setUp() {
        db = DerbyDb("rt_test_${System.nanoTime()}", dbDirectory = tmp, create = true)
        val ddl = """
            CREATE TABLE $schema.$table (
                C_VARCHAR   VARCHAR(64),
                C_INTEGER   INTEGER,
                C_BIGINT    BIGINT,
                C_DOUBLE    DOUBLE,
                C_DECIMAL   DECIMAL(12,3),
                C_BOOLEAN   BOOLEAN,
                C_DATE      DATE,
                C_TIME      TIME,
                C_TIMESTAMP TIMESTAMP
            )
        """.trimIndent()
        check(db.executeCommand(ddl)) { "Could not create test table" }
    }

    @AfterEach
    fun tearDown() {
        // Drop Derby connections so the temp dir can be cleaned. Derby's
        // embedded driver retains a lock on the database folder until shut
        // down; for the test it is enough that the JVM tears down and the
        // @TempDir is removed afterwards.
        try { db.executeCommand("DROP TABLE $schema.$table") } catch (_: Exception) {}
    }

    @Test
    fun `every column type survives export and re-import`() {
        // ── Seed row ─────────────────────────────────────────────────────────
        val origDate      = java.sql.Date.valueOf("2024-02-29")        // leap day
        val origTime      = Time.valueOf("13:45:30")
        val origTimestamp = Timestamp.valueOf("2025-05-23 09:15:42")
        val origDecimal   = BigDecimal("12345.678")

        db.executeCommand("""
            INSERT INTO $schema.$table VALUES (
                'hello world',
                42,
                9000000000,
                3.14159,
                12345.678,
                TRUE,
                DATE('2024-02-29'),
                TIME('13:45:30'),
                TIMESTAMP('2025-05-23 09:15:42')
            )
        """.trimIndent())

        // ── Export to xlsx ───────────────────────────────────────────────────
        val wbPath = tmp.resolve("roundtrip.xlsx")
        ExcelUtil.exportTablesToExcel(db, wbPath, listOf(table), schemaName = schema)
        assertTrue(Files.exists(wbPath), "workbook was not produced")

        // ── Wipe the table ───────────────────────────────────────────────────
        check(db.executeCommand("DELETE FROM $schema.$table"))
        assertEquals(0, rowCount(), "table should be empty before re-import")

        // ── Re-import (skipFirstRow because export writes a header) ─────────
        // The bad-rows file lands in a sibling directory of the workbook
        // (the import helper resolves "$workbook_basename/${table}_MissingRows.txt").
        Files.createDirectories(tmp.resolve("roundtrip"))
        ExcelUtil.importWorkbookToSchema(
            db,
            pathToWorkbook = wbPath,
            tableNames = listOf(table),
            schemaName = schema,
            skipFirstRow = true
        )

        // ── Verify ───────────────────────────────────────────────────────────
        val badRowsPath = tmp.resolve("roundtrip").resolve("${table}_MissingRows.txt")
        val badRows = if (Files.exists(badRowsPath)) Files.readString(badRowsPath) else "(no bad-rows file)"
        assertEquals(
            1, rowCount(),
            "exactly one row should have been re-imported. Bad rows file:\n$badRows"
        )
        val row = selectRow()

        assertEquals("hello world", row["C_VARCHAR"], "VARCHAR")
        assertEquals(42, (row["C_INTEGER"] as Number).toInt(), "INTEGER")
        assertEquals(9_000_000_000L, (row["C_BIGINT"] as Number).toLong(), "BIGINT")
        assertEquals(3.14159, (row["C_DOUBLE"] as Number).toDouble(), 1e-12, "DOUBLE")

        val decBack = row["C_DECIMAL"] as BigDecimal
        assertEquals(0, origDecimal.compareTo(decBack), "DECIMAL value (got $decBack)")

        assertEquals(true, row["C_BOOLEAN"], "BOOLEAN")

        // DATE: must round trip with no time-component drift.
        val dBack = row["C_DATE"] as java.sql.Date
        assertEquals(origDate.toString(), dBack.toString(), "DATE")

        // TIME: must round trip without the 1899-12-30 anchor leaking back.
        // This is the round-trip property the readCellAsObject patch enables.
        val tBack = row["C_TIME"] as Time
        assertEquals(origTime.toString(), tBack.toString(), "TIME")

        // TIMESTAMP: full precision down to seconds (fastexcel doesn't preserve
        // sub-second precision in standard date number formats).
        val tsBack = row["C_TIMESTAMP"] as Timestamp
        assertEquals(
            origTimestamp.toLocalDateTime().withNano(0),
            tsBack.toLocalDateTime().withNano(0),
            "TIMESTAMP"
        )
    }

    @Test
    fun `null values round trip as null when at least one column is populated`() {
        // fastexcel does not emit a row element when every cell in that row is
        // empty, so we anchor the row with a single non-null sentinel value
        // and verify that the remaining columns survive the round trip as
        // null. This matches what production data looks like (real rows
        // almost always have a primary-key or audit column populated).
        db.executeCommand("""
            INSERT INTO $schema.$table VALUES (
                'sentinel', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL
            )
        """.trimIndent())

        val wbPath = tmp.resolve("nulls.xlsx")
        ExcelUtil.exportTablesToExcel(db, wbPath, listOf(table), schemaName = schema)
        check(db.executeCommand("DELETE FROM $schema.$table"))
        Files.createDirectories(tmp.resolve("nulls"))
        ExcelUtil.importWorkbookToSchema(
            db, wbPath, listOf(table), schema, skipFirstRow = true
        )

        assertEquals(1, rowCount())
        val row = selectRow()
        assertEquals("sentinel", row["C_VARCHAR"])
        for ((k, v) in row) {
            if (k == "C_VARCHAR") continue
            assertNull(v, "$k should be null after round trip")
        }
    }

    @Test
    fun `boundary times - midnight and end of day - round trip`() {
        db.executeCommand("""
            INSERT INTO $schema.$table (C_VARCHAR, C_TIME) VALUES ('midnight', TIME('00:00:00'))
        """.trimIndent())
        db.executeCommand("""
            INSERT INTO $schema.$table (C_VARCHAR, C_TIME) VALUES ('eod', TIME('23:59:59'))
        """.trimIndent())

        val wbPath = tmp.resolve("times.xlsx")
        ExcelUtil.exportTablesToExcel(db, wbPath, listOf(table), schemaName = schema)
        check(db.executeCommand("DELETE FROM $schema.$table"))
        Files.createDirectories(tmp.resolve("times"))
        ExcelUtil.importWorkbookToSchema(
            db, wbPath, listOf(table), schema, skipFirstRow = true
        )

        val rows = db.fetchCachedRowSet(
            "SELECT C_VARCHAR, C_TIME FROM $schema.$table ORDER BY C_VARCHAR"
        )
        assertNotNull(rows)
        val pairs = mutableListOf<Pair<String, Time>>()
        while (rows!!.next()) {
            pairs.add(rows.getString(1) to rows.getTime(2))
        }
        assertEquals(2, pairs.size)
        // 'eod' sorts before 'midnight' alphabetically
        assertEquals("eod", pairs[0].first)
        assertEquals("23:59:59", pairs[0].second.toString())
        assertEquals("midnight", pairs[1].first)
        assertEquals("00:00:00", pairs[1].second.toString())
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun rowCount(): Int {
        val rs = db.fetchCachedRowSet("SELECT COUNT(*) FROM $schema.$table")
        assertNotNull(rs)
        rs!!.next()
        return rs.getInt(1)
    }

    /** Selects the (single) row and returns it as a column-name → value map. */
    private fun selectRow(): Map<String, Any?> {
        val rs = db.fetchCachedRowSet("SELECT * FROM $schema.$table")
        assertNotNull(rs)
        rs!!.next()
        val md = rs.metaData
        val result = linkedMapOf<String, Any?>()
        for (i in 1..md.columnCount) {
            result[md.getColumnName(i).uppercase()] = rs.getObject(i)
        }
        return result
    }
}
