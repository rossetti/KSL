/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.processview

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Holds the guides index's tables of the process-view examples to the examples, the Gradle tasks and the
 *  tutorials, in each direction:
 *
 *  - every example in a package is in its table, and every example in the table exists;
 *  - every task a row names runs that row's example;
 *  - the rows are in the order of the tutorial's cases, with the tutorial's tasks.
 *
 *  Skipped rather than failed when the index is absent, so a checkout without `docs/` still builds.
 */
class ProcessViewExampleTableTest {

    private val index: Path = Path.of("../docs/guides/README.md")
    private val buildScript: Path = Path.of("build.gradle.kts")
    private val sourceDir: Path = Path.of("src/main/kotlin/ksl/examples/general/processview")
    private val tutorials = mapOf("waits" to "ksl-waits-tutorial.md", "resources" to "ksl-resources-tutorial.md")

    private class Row(val case: Int, val example: String, val task: String)

    /** The rows of the index's table for the examples in `general.processview.<pkg>`. */
    private fun table(pkg: String): List<Row> {
        val lines = Files.readAllLines(index)
        val header = lines.indexOfFirst { it.startsWith("| # | Example, in `general.processview.$pkg`") }
        if (header < 0) return emptyList()
        val row = Regex("""^\|\s*(\d+)\s*\|\s*`(\w+)`\s*\|\s*`:KSLExamples:(\w+)`\s*\|$""")
        return lines.drop(header + 2).takeWhile { it.startsWith("|") }.mapNotNull { line ->
            row.find(line)?.let { Row(it.groupValues[1].toInt(), it.groupValues[2], it.groupValues[3]) }
        }
    }

    /** The case number and task of each case in the tutorial's own table of cases. */
    private fun tutorialCases(pkg: String): List<Pair<Int, String>> {
        val text = Files.readAllLines(Path.of("../docs/guides/${tutorials.getValue(pkg)}"))
        val row = Regex("""^\|\s*(\d+)\s*\|\s*\[.*\|\s*`:KSLExamples:(\w+)`\s*\|$""")
        return text.mapNotNull { line -> row.find(line)?.let { it.groupValues[1].toInt() to it.groupValues[2] } }
    }

    private fun examplesOnDisk(pkg: String): Set<String> =
        Files.list(sourceDir.resolve(pkg)).use { paths ->
            paths.filter { it.extension == "kt" }.map { it.nameWithoutExtension }.toList()
        }.toSet()

    @Test
    @DisplayName("the index's process-view tables list exactly the examples in each package")
    fun tablesMatchTheExamples() {
        assumeTrue(Files.isRegularFile(index), "guides index not present in this checkout")
        for (pkg in tutorials.keys) {
            val rows = table(pkg)
            assertTrue(rows.size >= 4, "the index's table for $pkg yields ${rows.size} rows: was it reformatted?")
            assertEquals(examplesOnDisk(pkg), rows.map { it.example }.toSet(), "the examples of $pkg against the index's table")
        }
    }

    @Test
    @DisplayName("every task the index names runs the example of its row")
    fun tasksRunTheirExamples() {
        assumeTrue(Files.isRegularFile(index), "guides index not present in this checkout")
        val script = Files.readString(buildScript)
        for (pkg in tutorials.keys) {
            for (r in table(pkg)) {
                assertTrue(script.contains("\"${r.task}\" to \"${r.example}Kt\""),
                    "build.gradle.kts has no task ${r.task} running ksl.examples.general.processview.$pkg.${r.example}Kt")
            }
        }
    }

    @Test
    @DisplayName("the index's rows follow the tutorials' cases")
    fun rowsFollowTheTutorials() {
        assumeTrue(Files.isRegularFile(index), "guides index not present in this checkout")
        for (pkg in tutorials.keys) {
            val cases = tutorialCases(pkg)
            assertTrue(cases.size >= 4, "the ${tutorials[pkg]} table of cases yields ${cases.size} rows: was it reformatted?")
            assertEquals(cases, table(pkg).map { it.case to it.task }, "the index's $pkg rows against the tutorial's cases")
        }
    }
}
