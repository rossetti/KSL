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

package ksl.examples.general.doc

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.test.fail

/**
 *  The transport tutorial quotes the examples; this checks that it still quotes them accurately.
 *
 *  `docs/guides/ksl-transport-tutorial.md` walks through thirteen examples and explains each one by
 *  showing its actual code. Those excerpts are copies, and a copy of source in a document is a
 *  claim that ages: rename a parameter, reorder an argument list, or change a policy's default and
 *  the tutorial goes on teaching what the code used to do. Nothing compiles a markdown file, so
 *  nothing would say so.
 *
 *  This test is what says so. For every fenced `kotlin` block that appears after a source file is
 *  named in its case, every non-elided line of the block must appear -- ignoring indentation -- in
 *  one of the files that case names. Containment rather than sequence, deliberately: the tutorial
 *  elides freely with `...` and quotes a method body without its class, and demanding contiguity
 *  would fail on formatting rather than on drift.
 *
 *  A block that appears before any file is named in its case is *not* checked, which is how the
 *  two illustrative fragments in the "The model" sections are allowed to be elided pseudo-code.
 *  [theTutorialIsActuallyBeingRead] is the guard against that exemption quietly swallowing
 *  everything.
 *
 *  Skipped rather than failed when the guide is absent, so a source-only checkout with no `docs/`
 *  tree still builds.
 *
 *  A second check guards the stronger promise the tutorial makes. Each case says it gives its
 *  example "in full", and [theFullListingsAreComplete] holds it to that: every executable line of
 *  every file named that way must appear somewhere on the page. Add a line to an example and
 *  forget the tutorial, and the build says which line.
 */
class TransportTutorialCodeTest {

    private val guide: Path = Path.of("../docs/guides/ksl-transport-tutorial.md")
    private val sourceRoot: Path = Path.of("src/main/kotlin")

    /** One fenced kotlin block, with the source files its case named before it. */
    private class Excerpt(
        val caseHeading: String,
        val startLine: Int,
        val candidates: List<String>,
        val lines: List<String>
    )

    private val caseHeading = Regex("""^## \d+\. .*$""")
    private val sourceName = Regex("""`(\w+\.kt)`""")

    /** Lines the tutorial writes itself rather than quotes: elisions and blanks. */
    private fun isQuoted(line: String): Boolean {
        val t = line.trim()
        return t.isNotEmpty() && t != "..." && t != "// ..."
    }

    private fun excerpts(): List<Excerpt> {
        val out = mutableListOf<Excerpt>()
        var heading = "(before the first case)"
        var named = mutableListOf<String>()
        val lines = Files.readAllLines(guide)
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                caseHeading.matches(line) -> {
                    heading = line.removePrefix("## ").trim()
                    named = mutableListOf()
                }
                line.trim() == "```kotlin" -> {
                    val body = mutableListOf<String>()
                    var j = i + 1
                    while (j < lines.size && lines[j].trim() != "```") {
                        body.add(lines[j])
                        j++
                    }
                    if (named.isNotEmpty()) {
                        out.add(Excerpt(heading, i + 1, named.toList(), body))
                    }
                    i = j
                }
                else -> sourceName.findAll(line).forEach { m ->
                    val n = m.groupValues[1]
                    if (n !in named) named.add(n)
                }
            }
            i++
        }
        return out
    }

    /**
     *  Every example source, by file name, as trimmed lines. A name can be shared by more than one
     *  file -- `TestAndRepairShopWithMovableResources.kt` is in both `book.chapter8` and
     *  `general.bookbundle` -- so each name maps to *every* file that carries it, and an excerpt is
     *  satisfied by any one of them rather than by an arbitrarily chosen first.
     */
    private fun sourcesByName(): Map<String, List<List<String>>> =
        Files.walk(sourceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension == "kt" }
                .toList()
        }.groupBy { it.name }
            .mapValues { (_, ps) -> ps.map { p -> Files.readAllLines(p).map { it.trim() } } }

    @Test
    @DisplayName("every code excerpt in the transport tutorial is still in the example it quotes")
    fun excerptsMatchTheirSources() {
        assumeTrue(Files.isRegularFile(guide), "guide not present in this checkout")
        val sources = sourcesByName()
        val failures = mutableListOf<String>()
        for (e in excerpts()) {
            val quoted = e.lines.filter { isQuoted(it) }.map { it.trim() }
            if (quoted.isEmpty()) continue
            val known = e.candidates.filter { it in sources }
            if (known.isEmpty()) {
                failures.add(
                    "${guide.fileName}:${e.startLine} (${e.caseHeading}) names " +
                        "${e.candidates} but no such file is under $sourceRoot"
                )
                continue
            }
            // The block must sit entirely inside one of the files its case named.
            val candidates = known.flatMap { name -> sources.getValue(name).map { name to it } }
            val fits = candidates.any { (_, body) -> quoted.all { it in body } }
            if (!fits) {
                val (best, bestBody) = candidates.minByOrNull { (_, body) ->
                    quoted.count { it !in body }
                }!!
                val missing = quoted.filter { it !in bestBody }
                failures.add(
                    "${guide.fileName}:${e.startLine} (${e.caseHeading}) quotes lines that are " +
                        "in no file it names (closest is $best):\n" +
                        missing.joinToString("\n") { "      $it" }
                )
            }
        }
        if (failures.isNotEmpty()) {
            fail(
                "the transport tutorial has drifted from the code it teaches:\n" +
                    failures.joinToString("\n") { "  $it" } +
                    "\nUpdate the tutorial to match the example, or the example to match itself.",
            )
        }
    }

    /**
     *  Anti-vacuity. The check above passes trivially if the tutorial is reformatted so that no
     *  block is associated with a file -- which is exactly what renaming a heading or dropping the
     *  backticks around a file name would do.
     */
    @Test
    @DisplayName("the tutorial's code excerpts are actually being read")
    fun theTutorialIsActuallyBeingRead() {
        assumeTrue(Files.isRegularFile(guide), "guide not present in this checkout")
        val found = excerpts()
        val cases = found.map { it.caseHeading }.toSet()
        val quotedLines = found.sumOf { e -> e.lines.count { isQuoted(it) } }
        if (found.size < 30 || cases.size < 10 || quotedLines < 250) {
            fail(
                "expected the tutorial to yield at least 30 checked excerpts across 10 cases and " +
                    "250 quoted lines; got ${found.size} excerpts, ${cases.size} cases, " +
                    "$quotedLines lines. If the tutorial was restructured, update this test — " +
                    "otherwise the check above passes vacuously.",
            )
        }
    }

    /** Files the tutorial claims to reproduce completely, from its own "`X.kt` in full" phrasing. */
    private fun claimedInFull(): List<String> =
        Regex("""`(\w+\.kt)`\s+in full""")
            .findAll(Files.readString(guide))
            .map { it.groupValues[1] }
            .distinct()
            .toList()

    /** Lines of a Kotlin source that a reader needs: not blank, not comment, not import or package. */
    private fun executableLines(body: List<String>): List<String> {
        val out = mutableListOf<String>()
        var inBlockComment = false
        for (raw in body) {
            val t = raw.trim()
            if (t.startsWith("/*")) inBlockComment = true
            if (inBlockComment) {
                if (t.contains("*/")) inBlockComment = false
                continue
            }
            if (t.isEmpty() || t.startsWith("//") || t.startsWith("*")) continue
            if (t.startsWith("import ") || t.startsWith("package ")) continue
            out.add(t)
        }
        return out
    }

    @Test
    @DisplayName("every example the tutorial gives \"in full\" really is there in full")
    fun theFullListingsAreComplete() {
        assumeTrue(Files.isRegularFile(guide), "guide not present in this checkout")
        val shown = excerpts().flatMap { e -> e.lines }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val sources = sourcesByName()
        val claimed = claimedInFull()
        val failures = mutableListOf<String>()
        var checked = 0
        for (name in claimed) {
            val bodies = sources[name]
            if (bodies == null) {
                failures.add("the tutorial says it gives $name in full, but no such file is under $sourceRoot")
                continue
            }
            // Ambiguous names: satisfied by whichever copy the page actually reproduces.
            val best = bodies.minByOrNull { b -> executableLines(b).count { it !in shown } }!!
            val code = executableLines(best)
            checked += code.size
            val missing = code.filter { it !in shown }
            if (missing.isNotEmpty()) {
                failures.add(
                    "$name is given \"in full\" but ${missing.size} of its ${code.size} " +
                        "executable lines are not on the page:\n" +
                        missing.take(20).joinToString("\n") { "      $it" } +
                        if (missing.size > 20) "\n      ... and ${missing.size - 20} more" else ""
                )
            }
        }
        if (claimed.size < 8 || checked < 1_000) {
            fail(
                "expected at least 8 examples claimed \"in full\" totalling 1,000 executable lines; " +
                    "got ${claimed.size} files and $checked lines. If the tutorial was restructured, " +
                    "update this test — otherwise the check below passes vacuously.",
            )
        }
        if (failures.isNotEmpty()) {
            fail(
                "the tutorial promises these examples in full and does not deliver them:\n" +
                    failures.joinToString("\n") { "  $it" } +
                    "\nAdd the missing lines to docs/guides/ksl-transport-tutorial.md, or stop " +
                    "saying \"in full\" for that example.",
            )
        }
    }
}
