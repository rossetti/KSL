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

package ksl.examples.general.agent

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.fail

/**
 *  Phase D — the agent guide's example table must match the filesystem.
 *
 *  This guard exists because the mismatch it checks for actually happened and went
 *  unnoticed for six weeks. A June refactor moved four agent examples out of
 *  `KSLExamples` into `KSLTestModels` on the reasoning that they were "prototypes";
 *  the effective criterion turned out to be whether an animation example imported
 *  them, not whether they were teaching material. `docs/guides/ksl-agent.md` went on
 *  listing all of them under a single `KSLExamples/.../agent/` heading, so the one
 *  document a learner reads pointed at files that were no longer there — including
 *  the clearest statechart-driven-behaviour example in the library.
 *
 *  Two directions are checked, and they fail for different reasons:
 *
 *   - **Every example the guide names must exist.** Catches the June regression: a
 *     file moved or renamed while the guide kept pointing at it.
 *   - **Every example that exists must be named.** Catches the opposite drift: a new
 *     example added to the teaching surface that no reader is ever told about.
 *
 *  The test is skipped rather than failed when the guide cannot be found, so it does
 *  not break a source-only or packaged checkout that has no `docs/` tree.
 */
class GuideExampleTableTest {

    private val guide: Path = Path.of("../docs/guides/ksl-agent.md")
    private val exampleDir: Path = Path.of("src/main/kotlin/ksl/examples/general/agent")

    /** Example names appearing in the guide's example table, e.g. `` `FlockingExample` ``. */
    private fun namedInGuide(): Set<String> {
        val row = Regex("""^\|\s*`(\w+Example)`\s*\|""", RegexOption.MULTILINE)
        return row.findAll(Files.readString(guide)).map { it.groupValues[1] }.toSet()
    }

    /** Example classes actually present on the teaching surface. */
    private fun presentOnDisk(): Set<String> =
        Files.list(exampleDir).use { paths ->
            paths.map { it.fileName.toString() }
                .filter { it.endsWith("Example.kt") }
                .map { it.removeSuffix(".kt") }
                .toList()
        }.toSet()

    @Test
    @DisplayName("D2: every example named in the guide exists in KSLExamples")
    fun everyNamedExampleExists() {
        assumeTrue(Files.isRegularFile(guide), "guide not present in this checkout")
        val missing = (namedInGuide() - presentOnDisk()).sorted()
        if (missing.isNotEmpty()) {
            fail(
                "the agent guide names examples that are not in " +
                    "KSLExamples/.../general/agent:\n" + missing.joinToString("\n") { "  $it" } +
                    "\nEither restore them to the teaching surface or correct the guide.",
            )
        }
    }

    @Test
    @DisplayName("D2: every example in KSLExamples is named in the guide")
    fun everyExistingExampleIsNamed() {
        assumeTrue(Files.isRegularFile(guide), "guide not present in this checkout")
        val undocumented = (presentOnDisk() - namedInGuide()).sorted()
        if (undocumented.isNotEmpty()) {
            fail(
                "these agent examples are on the teaching surface but absent from the " +
                    "guide's example table:\n" + undocumented.joinToString("\n") { "  $it" } +
                    "\nAdd a row to docs/guides/ksl-agent.md so readers can find them.",
            )
        }
    }

    /**
     *  Anti-vacuity: both tests above pass trivially if the table regex matches
     *  nothing, which is exactly what a reformatting of the guide would cause.
     */
    @Test
    @DisplayName("D2: the guide's example table is actually being parsed")
    fun theTableIsParsed() {
        assumeTrue(Files.isRegularFile(guide), "guide not present in this checkout")
        val named = namedInGuide()
        if (named.size < 10) {
            fail(
                "expected the guide's example table to yield at least 10 names, got " +
                    "${named.size}: $named. If the table was reformatted, update the " +
                    "regex in this test — otherwise the checks above pass vacuously.",
            )
        }
    }
}
