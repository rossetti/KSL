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

package ksl.app.config.experiment

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 *  Substrate tests for [parseManualCsv] — the design-points CSV
 *  parser hoisted to KSLCore in Phase D-Experiment.  Covers the
 *  parser's full error surface plus happy paths.  Each `Failure`
 *  branch is exercised individually, and multi-line error
 *  aggregation is verified separately so a bug in any one branch
 *  surfaces independently.
 *
 *  Backfilled in Phase E.3 — substrate API coverage for hoisted
 *  types.
 */
class ManualCsvImportTest {

    private val factors: List<FactorSpec> = listOf(
        FactorSpec(
            name = "A",
            levels = listOf(0.0, 10.0),
            binding = ControlBinding.Control("model.A")
        ),
        FactorSpec(
            name = "B",
            levels = listOf(0.0, 5.0),
            binding = ControlBinding.Control("model.B")
        )
    )

    private fun writeCsv(dir: Path, body: String): File {
        val file = dir.resolve("points.csv").toFile()
        file.writeText(body)
        return file
    }

    // ── Happy paths ──────────────────────────────────────────────────────

    @Test
    fun `happy path two factors no reps column parses to Ok with all points`(@TempDir dir: Path) {
        val file = writeCsv(dir, "A,B\n2.0,3.0\n7.5,1.0\n")
        val result = parseManualCsv(file, factors)
        val ok = assertIs<ManualCsvImportResult.Ok>(result)
        assertEquals(2, ok.points.size)
        assertEquals(mapOf("A" to 2.0, "B" to 3.0), ok.points[0].factorValues)
        assertEquals(mapOf("A" to 7.5, "B" to 1.0), ok.points[1].factorValues)
        assertEquals(null, ok.points[0].replications,
            "Replications must be null when no reps column is present.")
        assertEquals(null, ok.points[1].replications)
    }

    @Test
    fun `happy path with optional reps column populates per-point replications`(@TempDir dir: Path) {
        val file = writeCsv(dir, "A,B,reps\n2.0,3.0,5\n7.5,1.0,\n4.0,2.0,12\n")
        val result = parseManualCsv(file, factors)
        val ok = assertIs<ManualCsvImportResult.Ok>(result)
        assertEquals(3, ok.points.size)
        assertEquals(5, ok.points[0].replications)
        // Blank reps cell → null (inherits document default).
        assertEquals(null, ok.points[1].replications,
            "Blank reps cell must inherit the document default (null).")
        assertEquals(12, ok.points[2].replications)
    }

    @Test
    fun `blank rows in the middle of the file are skipped`(@TempDir dir: Path) {
        val file = writeCsv(dir, "A,B\n2.0,3.0\n\n\n7.5,1.0\n")
        val result = parseManualCsv(file, factors)
        val ok = assertIs<ManualCsvImportResult.Ok>(result)
        assertEquals(2, ok.points.size,
            "Mid-file blank lines must not produce design points or errors.")
    }

    @Test
    fun `extra unrecognised columns in the header are silently ignored`(@TempDir dir: Path) {
        // The parser's only recognised special column is `reps`; any
        // other header name beyond the declared factor columns is
        // silently ignored per the file's contract.
        val file = writeCsv(dir, "A,B,note,owner\n2.0,3.0,first-point,alice\n7.5,1.0,second,bob\n")
        val result = parseManualCsv(file, factors)
        val ok = assertIs<ManualCsvImportResult.Ok>(result)
        assertEquals(2, ok.points.size)
    }

    // ── Error paths ──────────────────────────────────────────────────────

    @Test
    fun `non-existent file produces a Failure with the read error`(@TempDir dir: Path) {
        val ghost = dir.resolve("does-not-exist.csv").toFile()
        val result = parseManualCsv(ghost, factors)
        val failure = assertIs<ManualCsvImportResult.Failure>(result)
        assertEquals(1, failure.errors.size)
        assertTrue(failure.errors[0].startsWith("could not read"),
            "Read failure must use the 'could not read' prefix; got: ${failure.errors[0]}")
        assertTrue(ghost.absolutePath in failure.errors[0],
            "Read failure must include the absolute path.")
    }

    @Test
    fun `empty file fails with the file is empty message`(@TempDir dir: Path) {
        val file = writeCsv(dir, "")
        val result = parseManualCsv(file, factors)
        val failure = assertIs<ManualCsvImportResult.Failure>(result)
        assertEquals(listOf("file is empty"), failure.errors)
    }

    @Test
    fun `missing header factor columns fail with the missing-columns message`(@TempDir dir: Path) {
        // Header has only A; B is missing.
        val file = writeCsv(dir, "A\n2.0\n")
        val result = parseManualCsv(file, factors)
        val failure = assertIs<ManualCsvImportResult.Failure>(result)
        assertEquals(1, failure.errors.size)
        assertTrue(failure.errors[0].contains("header is missing required factor column"),
            "Header-missing error must use the expected prefix; got: ${failure.errors[0]}")
        assertTrue(failure.errors[0].contains("B"),
            "Header-missing error must name the missing column 'B'.")
    }

    @Test
    fun `missing cell value on a row produces a per-row error`(@TempDir dir: Path) {
        // Row 2 (line 2) has only the A cell; B is missing.
        val file = writeCsv(dir, "A,B\n2.0\n")
        val result = parseManualCsv(file, factors)
        val failure = assertIs<ManualCsvImportResult.Failure>(result)
        assertTrue(failure.errors.any {
            it.contains("line 2") && it.contains("missing value for 'B'")
        }, "Expected a line-2 missing-value error for B; got: ${failure.errors}")
    }

    @Test
    fun `non-numeric token produces a per-row not-a-number error`(@TempDir dir: Path) {
        val file = writeCsv(dir, "A,B\n2.0,oops\n")
        val result = parseManualCsv(file, factors)
        val failure = assertIs<ManualCsvImportResult.Failure>(result)
        assertTrue(failure.errors.any {
            it.contains("line 2") &&
                it.contains("value for 'B' is not a number") &&
                it.contains("'oops'")
        }, "Expected a line-2 not-a-number error for B; got: ${failure.errors}")
    }

    @Test
    fun `value outside the factor range produces a per-row out-of-range error`(@TempDir dir: Path) {
        // Factor B's range is [0, 5]; supply 99 to trigger the error.
        val file = writeCsv(dir, "A,B\n2.0,99.0\n")
        val result = parseManualCsv(file, factors)
        val failure = assertIs<ManualCsvImportResult.Failure>(result)
        assertTrue(failure.errors.any {
            it.contains("line 2") &&
                it.contains("'B' value 99.0") &&
                it.contains("outside")
        }, "Expected a line-2 out-of-range error for B; got: ${failure.errors}")
    }

    @Test
    fun `bad reps token produces a per-row not-a-positive-integer error`(@TempDir dir: Path) {
        // reps cell on line 2 is non-numeric.
        val file = writeCsv(dir, "A,B,reps\n2.0,3.0,xyz\n")
        val result = parseManualCsv(file, factors)
        val failure = assertIs<ManualCsvImportResult.Failure>(result)
        assertTrue(failure.errors.any {
            it.contains("line 2") &&
                it.contains("reps token 'xyz' is not a positive integer")
        }, "Expected a line-2 bad-reps error; got: ${failure.errors}")
    }

    @Test
    fun `header-only file with no data rows fails with no data rows found`(@TempDir dir: Path) {
        val file = writeCsv(dir, "A,B\n")
        val result = parseManualCsv(file, factors)
        val failure = assertIs<ManualCsvImportResult.Failure>(result)
        assertEquals(listOf("no data rows found"), failure.errors)
    }

    // ── Aggregation ──────────────────────────────────────────────────────

    @Test
    fun `errors from multiple rows are aggregated into one Failure`(@TempDir dir: Path) {
        // Line 2: non-numeric for B.  Line 4: out-of-range for B.
        // Line 3 is a valid point — it should NOT produce an error
        // even though the whole result is a Failure.
        val file = writeCsv(dir, "A,B\n2.0,oops\n5.0,2.0\n3.0,99.0\n")
        val result = parseManualCsv(file, factors)
        val failure = assertIs<ManualCsvImportResult.Failure>(result)
        assertEquals(2, failure.errors.size,
            "Expected exactly 2 aggregated errors (one per bad row); got: ${failure.errors}")
        assertTrue(failure.errors.any { it.contains("line 2") && it.contains("not a number") })
        assertTrue(failure.errors.any { it.contains("line 4") && it.contains("outside") })
    }
}
