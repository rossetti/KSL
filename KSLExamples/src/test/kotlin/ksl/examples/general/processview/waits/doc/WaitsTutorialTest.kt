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

package ksl.examples.general.processview.waits.doc

import ksl.examples.general.processview.waits.CallCenter
import ksl.examples.general.processview.waits.KittingCell
import ksl.examples.general.processview.waits.ShuttleStop
import ksl.modeling.entity.Resource
import ksl.modeling.variable.RandomVariable
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.TriangularRV
import ksl.utilities.random.rvariable.UniformRV
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 *  Holds `docs/guides/ksl-waits-tutorial.md` to the examples it teaches, in five ways, each for a drift that
 *  happens without anyone noticing:
 *
 *  - **the code it quotes** is still in the files each case names, line for line;
 *  - **an example it gives in full** still has every executable line on the page;
 *  - **the output it shows** is still what the example prints, as pinned in `src/test/resources/pinned`;
 *  - **the parameter tables** still describe the models: each value is read from a built model;
 *  - **the guides index** still lists it.
 *
 *  And one check of the examples the page cannot make itself: a deterministic example repeats exactly over
 *  several replications, so nothing is carried from one replication into the next.
 *
 *  Skipped rather than failed when the guide is absent, so a checkout without `docs/` still builds.
 */
class WaitsTutorialTest {

    private val guide: Path = Path.of("../docs/guides/ksl-waits-tutorial.md")
    private val sourceRoot: Path = Path.of("src/main/kotlin")
    private val pins: Path = Path.of("src/test/resources/pinned/mains")
    private val examplePackage = "ksl.examples.general.processview.waits"

    private val caseHeading = Regex("""^## (\d+)\. .*$""")
    private val sourceName = Regex("""`(\w+\.kt)`""")

    private class Block(val case: Int, val language: String, val named: List<String>, val lines: List<String>)

    /** Every fenced block, with its case and the source files its case named before it. */
    private fun blocks(): List<Block> {
        val out = mutableListOf<Block>()
        var case = 0
        var named = mutableListOf<String>()
        val lines = Files.readAllLines(guide)
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val heading = caseHeading.find(line)
            when {
                heading != null -> {
                    case = heading.groupValues[1].toInt()
                    named = mutableListOf()
                }
                line.startsWith("```") && line.trim() != "```" -> {
                    val language = line.trim().removePrefix("```")
                    val body = mutableListOf<String>()
                    var j = i + 1
                    while (j < lines.size && lines[j].trim() != "```") {
                        body.add(lines[j]); j++
                    }
                    out.add(Block(case, language, named.toList(), body))
                    i = j
                }
                else -> sourceName.findAll(line).forEach { m -> if (m.groupValues[1] !in named) named.add(m.groupValues[1]) }
            }
            i++
        }
        return out
    }

    private fun sources(): Map<String, List<List<String>>> =
        Files.walk(sourceRoot).use { paths -> paths.filter { Files.isRegularFile(it) && it.extension == "kt" }.toList() }
            .groupBy { it.name }
            .mapValues { (_, ps) -> ps.map { p -> Files.readAllLines(p).map { it.trim() } } }

    /** Lines of a source a reader needs: not blank, comment, import or package. */
    private fun executableLines(body: List<String>): List<String> {
        val out = mutableListOf<String>()
        var inComment = false
        for (t in body.map { it.trim() }) {
            if (t.startsWith("/*")) inComment = true
            if (inComment) { if (t.contains("*/")) inComment = false; continue }
            if (t.isEmpty() || t.startsWith("//") || t.startsWith("*")) continue
            if (t.startsWith("import ") || t.startsWith("package ")) continue
            out.add(t)
        }
        return out
    }

    @Test
    @DisplayName("every code block of the waits tutorial is still in a file its case names")
    fun quotedCodeMatchesTheExamples() {
        assumeTrue(Files.isRegularFile(guide), "guide not present in this checkout")
        val sources = sources()
        val code = blocks().filter { it.language == "kotlin" }
        assertTrue(code.size >= 20 && code.map { it.case }.toSet() == setOf(1, 2, 3, 4, 5, 6, 7, 8),
            "expected at least 20 code blocks across the eight cases; got ${code.size}, so this test would pass with too little to check")
        val failures = mutableListOf<String>()
        for (b in code) {
            val quoted = b.lines.map { it.trim() }.filter { it.isNotEmpty() && it != "..." }
            val bodies = b.named.flatMap { sources[it] ?: emptyList() }
            if (bodies.isEmpty()) { failures += "case ${b.case}: a block names no file that exists (${b.named})"; continue }
            if (bodies.none { body -> quoted.all { it in body } }) {
                val best = bodies.minBy { body -> quoted.count { it !in body } }
                failures += "case ${b.case}: lines in no file it names:\n" + quoted.filter { it !in best }.joinToString("\n") { "      $it" }
            }
        }
        if (failures.isNotEmpty()) fail("the waits tutorial has drifted from its examples:\n" + failures.joinToString("\n"))
    }

    @Test
    @DisplayName("every example the waits tutorial gives in full is on the page in full")
    fun fullListingsAreComplete() {
        assumeTrue(Files.isRegularFile(guide), "guide not present in this checkout")
        val shown = blocks().filter { it.language == "kotlin" }.flatMap { it.lines }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val claimed = Regex("""`(\w+\.kt)`\s+in full""").findAll(Files.readString(guide)).map { it.groupValues[1] }.distinct().toList()
        val sources = sources()
        assertEquals(setOf("ImpatientCallers.kt", "FirstFreeOfTwo.kt", "ShuttleStop.kt", "KittingWithExpediting.kt", "HospitalBeds.kt", "TwoColourBin.kt", "TimedAuction.kt"), claimed.toSet(),
            "the files the tutorial claims to give in full")
        val failures = mutableListOf<String>()
        for (name in claimed) {
            val code = executableLines(sources.getValue(name).single())
            val missing = code.filter { it !in shown }
            if (missing.isNotEmpty()) failures += "$name: ${missing.size} of ${code.size} lines are not on the page:\n" +
                missing.joinToString("\n") { "      $it" }
        }
        if (failures.isNotEmpty()) fail("the waits tutorial promises these examples in full and does not give them:\n" + failures.joinToString("\n"))
    }

    @Test
    @DisplayName("the output the waits tutorial shows is what each example prints")
    fun shownOutputIsThePinnedOutput() {
        assumeTrue(Files.isRegularFile(guide), "guide not present in this checkout")
        val mainOf = mapOf(
            1 to "ImpatientCallersKt", 2 to "WalkInHealthClinicViaTimedSeizeKt", 3 to "FirstFreeOfTwoKt",
            4 to "ShuttleStopKt", 5 to "KittingWithExpeditingKt", 6 to "HospitalBedsKt", 7 to "TwoColourBinKt", 8 to "TimedAuctionKt"
        )
        val outputs = blocks().filter { it.language == "text" }
        assertEquals(setOf(1, 2, 3, 4, 5, 6, 7, 8), outputs.map { it.case }.toSet(), "each case shows its output")
        val failures = mutableListOf<String>()
        for (b in outputs) {
            val pin = pins.resolve("$examplePackage.${mainOf.getValue(b.case)}.txt")
            val printed = Files.readAllLines(pin).map { it.trimEnd() }.filter { it.isNotBlank() }
            val shown = b.lines.map { it.trimEnd() }.filter { it.isNotBlank() }
            val start = (0..printed.size - shown.size).firstOrNull { s -> shown.indices.all { printed[s + it] == shown[it] } }
            if (start == null) failures += "case ${b.case}: the output shown is not what ${pin.fileName} pins"
        }
        if (failures.isNotEmpty()) fail("the waits tutorial shows numbers its examples no longer print:\n" + failures.joinToString("\n"))
    }

    /** The second column of the row of case [case]'s system table whose first column is [label]. */
    private fun cell(case: Int, label: String): String {
        val text = Files.readAllLines(guide)
        val start = text.indexOfFirst { it.startsWith("## $case. ") }
        val end = text.drop(start + 1).indexOfFirst { it.startsWith("## ") }.let { if (it < 0) text.size else start + 1 + it }
        val row = text.subList(start, end).firstOrNull { it.startsWith("| $label |") }
            ?: fail("case $case has no table row '$label'")
        return row.split("|")[2].trim()
    }

    private fun expectCell(case: Int, label: String, expected: String, atStart: Boolean = false) {
        val text = cell(case, label)
        assertTrue(
            if (atStart) text.startsWith(expected) else text.contains(expected),
            "case $case, row '$label' reads '$text', but the model it describes has '$expected'"
        )
    }

    private fun Model.rv(name: String) = (getModelElement(name) as RandomVariable).initialRandomSource

    private fun n(x: Double): String = if (x == Math.floor(x)) x.toLong().toString() else x.toString()

    @Test
    @DisplayName("the waits tutorial's parameter tables describe the models the examples build")
    fun systemTablesDescribeTheModels() {
        assumeTrue(Files.isRegularFile(guide), "guide not present in this checkout")

        val m1 = Model("Table1", autoCSVReports = false)
        val center = CallCenter(m1, name = "CallCenter")
        expectCell(1, "Time between calls", "${n(center.arrivalRate)} calls a minute")
        expectCell(1, "Number of agents", n((m1.getModelElement("Agents") as Resource).initialCapacity.toDouble()), atStart = true)
        expectCell(1, "Call length", "mean ${n((m1.rv("ServiceTime") as ExponentialRV).mean)} minutes")
        expectCell(1, "Patience", "mean ${n((m1.rv("Patience") as ExponentialRV).mean)} minutes")

        val m4 = Model("Table4", autoCSVReports = false)
        val stop = ShuttleStop(m4, name = "Stop")
        expectCell(4, "Seats on the shuttle", n(stop.capacity.toDouble()), atStart = true)
        expectCell(4, "Longest hold with a rider aboard", "${n(stop.maxHold)} minutes", atStart = true)
        val trip = m4.rv("RoundTrip") as TriangularRV
        expectCell(4, "Round trip", "${n(trip.min)}, ${n(trip.mode)}, ${n(trip.max)} minutes")
        expectCell(4, "Rider's patience", "mean ${n((m4.rv("Patience") as ExponentialRV).mean)} minutes")

        val m5 = Model("Table5", autoCSVReports = false)
        val cell5 = KittingCell(m5, name = "Cell")
        expectCell(5, "Time between kits", "mean ${n((m5.rv("TimeBetweenKits") as ExponentialRV).mean)} minutes")
        val assembly = m5.rv("AssemblyTime") as UniformRV
        expectCell(5, "Assembly time", "${n(assembly.min)} to ${n(assembly.max)} minutes")
        expectCell(5, "Time between the supervisor's recalls", "mean ${n((m5.rv("TimeBetweenRecalls") as ExponentialRV).mean)} minutes")
        expectCell(5, "Rework job", "${n((m5.rv("ReworkTime") as ConstantRV).constVal)} minutes", atStart = true)
        expectCell(5, "Wait before expediting", "${n(cell5.expediteAfter)} minutes", atStart = true)
        expectCell(5, "Expedited kit's lead time", "${n(cell5.expediteLeadTime)} minutes", atStart = true)
    }

    @Test
    @DisplayName("the waits tutorial is listed in the guides index")
    fun listedInTheIndex() {
        val index = Path.of("../docs/guides/README.md")
        assumeTrue(Files.isRegularFile(index), "guides index not present in this checkout")
        assertTrue(Files.readString(index).contains("ksl-waits-tutorial.md"), "docs/guides/README.md does not list ksl-waits-tutorial.md")
    }

    @Test
    @DisplayName("the kitting cell with constant times repeats exactly in every replication")
    fun deterministicRunRepeats() {
        val m = Model("Repeat", autoCSVReports = false)
        KittingCell(
            m, timeBetweenKits = ConstantRV(20.0), assemblyTime = ConstantRV(8.0),
            timeBetweenRecalls = ConstantRV(30.0), reworkTime = ConstantRV(10.0), name = "Cell"
        )
        m.numberOfReplications = 3
        m.lengthOfReplication = 1_000.0
        m.simulate()
        for (c in m.counters) {
            val s = c.acrossReplicationStatistic
            assertEquals(0.0, s.variance, "${c.name} differs between replications: state carried from one to the next")
        }
    }
}
