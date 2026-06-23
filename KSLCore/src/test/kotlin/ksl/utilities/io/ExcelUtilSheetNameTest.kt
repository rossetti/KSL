package ksl.utilities.io

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pure-function unit tests for [ExcelUtil.createSafeSheetName] — the
 * replacement for POI's WorkbookUtil.createSafeSheetName. These rules
 * (invalid-char scrubbing, trim, 31-char truncation, empty fallback) are
 * relied on by the multi-sheet export path, so they are pinned here with
 * fast, I/O-free assertions.
 */
class ExcelUtilSheetNameTest {

    @Test
    fun `valid name is returned unchanged`() {
        assertEquals("ACROSS_REP_VIEW", ExcelUtil.createSafeSheetName("ACROSS_REP_VIEW"))
    }

    @Test
    fun `each invalid character is replaced with a space`() {
        // Invalid set per ExcelUtil: \ / ? * [ ] :
        assertEquals("a b c d e f g h", ExcelUtil.createSafeSheetName("a\\b/c?d*e[f]g:h"))
    }

    @Test
    fun `leading and trailing invalid characters are trimmed away`() {
        assertEquals("name", ExcelUtil.createSafeSheetName("/name:"))
    }

    @Test
    fun `names longer than 31 characters are truncated to 31`() {
        val safe = ExcelUtil.createSafeSheetName("X".repeat(40))
        assertEquals(31, safe.length)
        assertEquals("X".repeat(31), safe)
    }

    @Test
    fun `blank name falls back to Sheet`() {
        assertEquals("Sheet", ExcelUtil.createSafeSheetName("   "))
    }

    @Test
    fun `all-invalid name falls back to Sheet`() {
        // "///" -> "   " -> trim -> "" -> "Sheet"
        assertEquals("Sheet", ExcelUtil.createSafeSheetName("///"))
    }

    @Test
    fun `two identifiers sharing a 31-char prefix collapse to the same safe name`() {
        // Documents the precondition behind the sheet-name dedup loop in
        // exportTablesToExcel: DB identifiers longer than 31 chars that share
        // their first 31 characters produce IDENTICAL safe names. (The two
        // inputs below differ only in their final character.) This is the
        // collision the dedup loop must disambiguate — see the hazard note in
        // ExcelUtilMultiSheetExportTest's companion discussion.
        val a = "REALLY_LONG_TABLE_NAME_PREFIX_AAA_SUFFIX1"
        val b = "REALLY_LONG_TABLE_NAME_PREFIX_AAA_SUFFIX2"
        assertEquals(
            ExcelUtil.createSafeSheetName(a),
            ExcelUtil.createSafeSheetName(b),
            "long shared-prefix identifiers must collapse to the same 31-char safe name"
        )
    }
}
