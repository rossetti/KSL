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

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Covers the lazy-subdir + suppress-out-file behaviour added so that
 *  flat-mode designed-experiment / scenario runs don't litter the
 *  shared analysis directory with empty per-run log files and four
 *  empty format subdirectories.  See the KDoc on
 *  `ksl.utilities.io.OutputDirectory` for context.
 */
class OutputDirectoryTest {

    // ── autoCreateOutFile ─────────────────────────────────────────────────

    @Test
    fun `autoCreateOutFile = true creates the out file eagerly (default behaviour)`(
        @TempDir tempDir: Path
    ) {
        val od = OutputDirectory(tempDir.resolve("od1"), outFileName = "myLog.txt")
        assertTrue(Files.exists(od.outDir), "outDir should exist")
        assertTrue(
            Files.exists(od.outDir.resolve("myLog.txt")),
            "outFile should be created when autoCreateOutFile = true"
        )
    }

    @Test
    fun `autoCreateOutFile = false skips file creation and out becomes a discard writer`(
        @TempDir tempDir: Path
    ) {
        val od = OutputDirectory(
            tempDir.resolve("od2"),
            outFileName = "shouldNotExist.txt",
            autoCreateOutFile = false
        )
        assertTrue(Files.exists(od.outDir), "outDir is always created eagerly")
        assertFalse(
            Files.exists(od.outDir.resolve("shouldNotExist.txt")),
            "outFile must not be created when autoCreateOutFile = false"
        )
        // Writing to the discard writer is a no-op and must not
        // materialise a file.
        od.out.println("this disappears")
        od.out.flush()
        assertFalse(
            Files.exists(od.outDir.resolve("shouldNotExist.txt")),
            "writing to the discard out must not create the file on disk"
        )
    }

    // ── Lazy subdirectories ───────────────────────────────────────────────

    @Test
    fun `subdirectories are NOT created until their properties are accessed`(
        @TempDir tempDir: Path
    ) {
        val od = OutputDirectory(
            tempDir.resolve("od3"),
            autoCreateOutFile = false   // also keeps the file from appearing
        )
        // Construction is complete.  No subdir should exist yet.
        for (name in listOf("excelDir", "dbDir", "csvDir", "plotDir")) {
            assertFalse(
                Files.exists(od.outDir.resolve(name)),
                "$name must not exist before its property is accessed"
            )
        }
        // outDir is the only directory that should exist (and it must
        // be empty).
        assertTrue(Files.exists(od.outDir))
        Files.list(od.outDir).use { stream ->
            val children = stream.toList()
            assertTrue(
                children.isEmpty(),
                "outDir should be empty, but contained: $children"
            )
        }
    }

    @Test
    fun `accessing a subdirectory property creates only that subdirectory`(
        @TempDir tempDir: Path
    ) {
        val od = OutputDirectory(
            tempDir.resolve("od4"),
            autoCreateOutFile = false
        )
        // Touch csvDir only.
        val csv = od.csvDir
        assertEquals(od.outDir.resolve("csvDir"), csv)
        assertTrue(Files.exists(csv), "csvDir should exist after access")
        // The other three should still not exist.
        assertFalse(Files.exists(od.outDir.resolve("excelDir")))
        assertFalse(Files.exists(od.outDir.resolve("dbDir")))
        assertFalse(Files.exists(od.outDir.resolve("plotDir")))
    }

    @Test
    fun `accessing all subdirectories creates all of them (backward-compat behaviour)`(
        @TempDir tempDir: Path
    ) {
        val od = OutputDirectory(
            tempDir.resolve("od5"),
            autoCreateOutFile = false
        )
        // Reading every property materialises every directory — same
        // effective behaviour as the previous eager initialisation.
        listOf(od.excelDir, od.dbDir, od.csvDir, od.plotDir).forEach {
            assertTrue(Files.exists(it), "$it should exist after property access")
        }
    }

    // ── toString does not materialise the lazy subdirs ────────────────────

    @Test
    fun `toString does not trigger subdirectory creation`(@TempDir tempDir: Path) {
        val od = OutputDirectory(
            tempDir.resolve("od6"),
            autoCreateOutFile = false
        )
        // Calling toString should be side-effect-free w.r.t. the lazy
        // subdirs.  This is what lets diagnostic prints stay quiet.
        val s = od.toString()
        assertTrue(s.contains("excelDir"), "toString should mention excelDir")
        for (name in listOf("excelDir", "dbDir", "csvDir", "plotDir")) {
            assertFalse(
                Files.exists(od.outDir.resolve(name)),
                "$name must not be created by toString()"
            )
        }
    }
}
