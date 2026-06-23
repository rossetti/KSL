package ksl.utilities.io

import ksl.utilities.io.dbutil.DerbyDb
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.zip.ZipFile

/**
 * Multi-sheet export hardening for [ExcelUtil.exportTablesToExcel] and the
 * [ksl.utilities.io.dbutil.DatabaseIOIfc.exportToExcel] facade.
 *
 * This is the regression guard for the fastexcel single-OPC-zip constraint:
 * a worksheet's zip entry must be closed (via Worksheet.finish) before the
 * next worksheet opens its entry. Before that fix, `exportAsWorkSheet` ended
 * each sheet with Worksheet.flush() — which opens the entry but never closes
 * it — so any export of two or more tables corrupted the entry ordering and
 * Workbook.close() threw "not current zip current".
 *
 * The pre-existing `ExcelUtilDbRoundTripTest` only ever exported a single
 * table, so it could not surface the bug. Every test here exports two or more
 * sheets on purpose.
 *
 * The workbook is validated with the JDK's [ZipFile] rather than the fastexcel
 * reader: fastexcel is an `implementation` dependency of KSLCore and is not on
 * the test compile classpath, and opening the .xlsx as a zip directly verifies
 * the OPC entry integrity that the bug corrupted. The round-trip test proves
 * sheet names and cell data via ExcelUtil's own import path.
 *
 * Derby is used (matching ExcelUtilDbRoundTripTest) because it honours
 * declared column types on the round trip.
 */
class ExcelUtilMultiSheetExportTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var db: ksl.utilities.io.dbutil.Database
    private val schema = "APP"
    private val tables = listOf("T1", "T2", "T3", "T4", "T5")

    @BeforeEach
    fun setUp() {
        db = DerbyDb("ms_test_${System.nanoTime()}", dbDirectory = tmp, create = true)
        // Each table gets a distinct row count (table i -> i+1 rows) so the
        // round-trip test can verify rows land in the right sheet/table.
        for ((i, t) in tables.withIndex()) {
            check(db.executeCommand("CREATE TABLE $schema.$t (ID INTEGER, LABEL VARCHAR(32))")) {
                "could not create table $t"
            }
            for (r in 0..i) {
                check(db.executeCommand("INSERT INTO $schema.$t VALUES ($r, '${t}_row$r')"))
            }
        }
    }

    @AfterEach
    fun tearDown() {
        for (t in tables) {
            try { db.executeCommand("DROP TABLE $schema.$t") } catch (_: Exception) {}
        }
    }

    /**
     * Opens the workbook as an OPC zip (throws if the archive is corrupt) and
     * returns the number of `xl/worksheets/sheetN.xml` parts it contains.
     */
    private fun worksheetPartCount(wb: Path): Int {
        val pattern = Regex("xl/worksheets/sheet\\d+\\.xml")
        ZipFile(wb.toFile()).use { zip ->
            return zip.entries().asSequence().count { pattern.matches(it.name) }
        }
    }

    @Test
    fun `two-table export is the minimal regression reproducer`() {
        // Two sheets is the smallest case that opens a second zip entry and
        // therefore the smallest case that failed before the finish() fix.
        val wb = tmp.resolve("two.xlsx")
        ExcelUtil.exportTablesToExcel(db, wb, listOf("T1", "T2"), schemaName = schema)
        assertTrue(Files.exists(wb), "workbook was not produced")
        assertEquals(2, worksheetPartCount(wb), "expected exactly two worksheet parts")
    }

    @Test
    fun `multi-table export writes one valid worksheet part per table`() {
        val wb = tmp.resolve("multi.xlsx")
        ExcelUtil.exportTablesToExcel(db, wb, tables, schemaName = schema)
        assertTrue(Files.exists(wb), "workbook was not produced")
        // A corrupt entry sequence (the pre-fix failure mode) makes ZipFile
        // throw or under-count here.
        assertEquals(tables.size, worksheetPartCount(wb), "expected one worksheet part per table")
    }

    @Test
    fun `export through the database facade exports all tables`() {
        // This is the path Ch5Example1 and the Swing Results "export all
        // tables" button actually take (DatabaseIOIfc.exportToExcel ->
        // exportTablesToExcel). It exports tables (and views) for the schema;
        // this fixture has no views, so the count equals the table count.
        db.exportToExcel(schemaName = schema, wbName = "facade", wbDirectory = tmp)
        val wb = tmp.resolve("facade.xlsx")
        assertTrue(Files.exists(wb), "facade did not produce a workbook")
        assertTrue(
            worksheetPartCount(wb) >= tables.size,
            "facade workbook should contain at least one part per table"
        )
    }

    @Test
    fun `every sheet survives re-import preserving per-table row counts`() {
        val wb = tmp.resolve("roundtrip.xlsx")
        ExcelUtil.exportTablesToExcel(db, wb, tables, schemaName = schema)
        val before = tables.associateWith { rowCount(it) }

        for (t in tables) check(db.executeCommand("DELETE FROM $schema.$t"))
        // Import helper writes "<workbook-stem>/<table>_MissingRows.txt".
        Files.createDirectories(tmp.resolve("roundtrip"))
        ExcelUtil.importWorkbookToSchema(
            db, wb, tables, schemaName = schema, skipFirstRow = true
        )

        for (t in tables) {
            assertEquals(before[t], rowCount(t), "row count mismatch for table $t after round trip")
        }
    }

    @Test
    fun `tables sharing a 31-char prefix get distinct sheet names without hanging`() {
        // Two identifiers whose first 31 characters are identical. createSafeSheetName
        // truncates both to the same 31-char value, so the export must disambiguate
        // them. The old dedup loop appended the "_n" counter BEFORE truncating, which
        // discarded the counter for >=31-char names — producing the same string every
        // attempt and spinning forever. The preemptive timeout fails fast (rather than
        // hanging the suite) if that regression returns.
        val shared = "T" + "X".repeat(30)        // 31-char shared prefix
        val nameA = "${shared}_ALPHA"            // 37 chars
        val nameB = "${shared}_BETA"             // 36 chars
        check(db.executeCommand("CREATE TABLE $schema.$nameA (ID INTEGER)"))
        check(db.executeCommand("CREATE TABLE $schema.$nameB (ID INTEGER)"))
        check(db.executeCommand("INSERT INTO $schema.$nameA VALUES (1)"))
        check(db.executeCommand("INSERT INTO $schema.$nameB VALUES (2)"))

        val wb = tmp.resolve("collide.xlsx")
        assertTimeoutPreemptively(Duration.ofSeconds(20)) {
            ExcelUtil.exportTablesToExcel(db, wb, listOf(nameA, nameB), schemaName = schema)
        }

        assertEquals(2, worksheetPartCount(wb), "expected two worksheet parts")
        val names = sheetNames(wb)
        assertEquals(2, names.size, "workbook.xml should declare two sheets; got $names")
        assertEquals(2, names.toSet().size, "the two sheet names must be distinct; got $names")
        for (n in names) {
            assertTrue(n.length <= 31, "sheet name '$n' exceeds Excel's 31-char limit")
        }

        try { db.executeCommand("DROP TABLE $schema.$nameA") } catch (_: Exception) {}
        try { db.executeCommand("DROP TABLE $schema.$nameB") } catch (_: Exception) {}
    }

    /** Extracts the declared sheet names from xl/workbook.xml (no fastexcel dependency). */
    private fun sheetNames(wb: Path): List<String> {
        ZipFile(wb.toFile()).use { zip ->
            val entry = zip.getEntry("xl/workbook.xml") ?: return emptyList()
            val xml = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            return Regex("""<sheet\b[^>]*?\bname="([^"]*)"""")
                .findAll(xml).map { it.groupValues[1] }.toList()
        }
    }

    private fun rowCount(table: String): Int {
        val rs = db.fetchCachedRowSet("SELECT COUNT(*) FROM $schema.$table")
        assertNotNull(rs)
        rs!!.next()
        return rs.getInt(1)
    }
}
