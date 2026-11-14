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

package ksl.examples.families

import ksl.examples.book.chapter6.SoccerMomV3
import ksl.examples.book.chapter6.interaction.SoccerMomViaBlockage
import ksl.examples.book.chapter6.interaction.SoccerMomViaBlockingActivity
import ksl.examples.book.chapter6.interaction.SoccerMomViaHoldQ
import ksl.examples.book.chapter6.interaction.SoccerMomViaRawSuspend
import ksl.examples.book.chapter6.interaction.SoccerMomViaSignal
import ksl.examples.book.chapter6.interaction.SoccerMomViaSyncWith
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ksl.examples.book.chapter6.SoccerMomWithSuspensions as Ch6SoccerMomWithSuspensions
import ksl.examples.book.chapter6.interaction.SoccerMomWithSuspensions as InteractionSoccerMomWithSuspensions

/**
 *  Families of examples that model one system in different ways must agree. Each family checks the process
 *  view from another angle:
 *  - the Soccer Mom variants of chapter 6, which coordinate a mother and daughter with a hold queue, a
 *    signal, suspensions, a blockage, a blocking activity and `syncWith`: their timelines;
 *  - the three active-resource models of the WSC 2025 paper (a blocking queue, a hold queue, a signal):
 *    every quantity they share, exactly;
 *  - the walk-in health clinic against the same clinic reneging by `seizeOrTimeout`: every quantity;
 *  - the STEM fair's capacity schedule against the same capacity changes made by a resource behavior:
 *    every quantity.
 *
 *  The models with random times run each in a JVM of its own, as the pinned-results test runs them, so that
 *  they never share the default stream provider; the Soccer Mom variants have constant times and run here.
 */
class ExampleFamiliesTest {

    // ---------- the Soccer Mom variants ----------

    /** The times printed for each milestone of the variant built by [build], in its first replication. */
    private fun soccerMomTimeline(build: (ModelElement) -> Any): Map<String, Double> {
        val model = Model("SoccerMom${System.nanoTime()}", autoCSVReports = false)
        build(model)
        model.lengthOfReplication = 150.0
        model.numberOfReplications = 1
        val captured = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(captured, true))
        try {
            model.simulate()
        } finally {
            System.setOut(original)
        }
        val milestones = linkedMapOf(
            "arrived at game" to "arrived at game", "exited the van" to "exited the van",
            "starting playing" to "starting playing", "finished playing" to "finished playing",
            "completed errands" to "completed errands", "entered van" to "entered van",
            "driving home" to "driving home", "arrived home" to "arrived home"
        )
        val seen = linkedMapOf<String, Double>()
        for (line in captured.toString().lineSequence()) {
            val m = Regex("""^([\d.]+)> (.*)$""").find(line.trim()) ?: continue
            val time = m.groupValues[1].toDouble()
            val text = m.groupValues[2]
            for ((key, phrase) in milestones) {
                if (key !in seen && (text.endsWith(phrase) || text.contains("$phrase,") || text.contains("$phrase "))) seen[key] = time
            }
        }
        return seen
    }

    @Test
    @DisplayName("the Soccer Mom variants agree on their timelines, in each group of the same parameters")
    fun soccerMomVariantsAgree() {
        // the daughter plays for 30
        val shortPlay = mapOf(
            "SoccerMomV3" to soccerMomTimeline { SoccerMomV3(it) },
            "SoccerMomViaHoldQ" to soccerMomTimeline { SoccerMomViaHoldQ(it) }
        )
        // the daughter plays for 60
        val longPlay = mapOf(
            "SoccerMomViaRawSuspend" to soccerMomTimeline { SoccerMomViaRawSuspend(it) },
            "SoccerMomViaSignal" to soccerMomTimeline { SoccerMomViaSignal(it) },
            "SoccerMomViaSyncWith" to soccerMomTimeline { SoccerMomViaSyncWith(it) },
            "SoccerMomViaBlockingActivity" to soccerMomTimeline { SoccerMomViaBlockingActivity(it) },
            "interaction.SoccerMomWithSuspensions" to soccerMomTimeline { InteractionSoccerMomWithSuspensions(it) },
            "chapter6.SoccerMomWithSuspensions" to soccerMomTimeline { Ch6SoccerMomWithSuspensions(it) },
            "SoccerMomViaBlockage" to soccerMomTimeline { SoccerMomViaBlockage(it) }
        )
        for ((group, members) in listOf("30" to shortPlay, "60" to longPlay)) {
            val (firstName, first) = members.entries.first()
            assertEquals(8, first.size, "$firstName prints all eight milestones: $first")
            for ((name, timeline) in members) assertEquals(first, timeline, "play $group: $name against $firstName")
        }
        assertEquals(109.0, shortPlay.getValue("SoccerMomV3")["arrived home"])
        assertEquals(124.0, longPlay.getValue("SoccerMomViaRawSuspend")["arrived home"])
    }

    // ---------- models with random times, each in a JVM of its own ----------

    /** Every response and counter of the model of class [className], by name without the model's prefix. */
    private fun quantities(className: String): Map<String, String> {
        val out = Files.createTempFile("family", ".txt")
        val work = Files.createTempDirectory("family")
        try {
            val java = File(System.getProperty("java.home"), "bin/java").absolutePath
            val silent = File("src/test/resources/pinned/logback-silent.xml").absolutePath
            val invariants = System.getProperty("ksl.checkInvariants")?.let { listOf("-Dksl.checkInvariants=$it") } ?: emptyList()
            val process = ProcessBuilder(
                listOf(java, "-Djava.awt.headless=true", "-Dlogback.configurationFile=$silent") + invariants +
                    listOf("-cp", System.getProperty("java.class.path"),
                        "ksl.examples.release.PinnedModelRunKt", "class", className, out.toString())
            ).directory(work.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            assertTrue(process.waitFor(300, TimeUnit.SECONDS), "$className did not finish")
            assertEquals(0, process.exitValue(), "$className exited with ${process.exitValue()}")
            return out.toFile().readLines().mapNotNull { line ->
                val m = Regex("""^[RC] (\S+) (.*)$""").find(line) ?: return@mapNotNull null
                m.groupValues[1].replace(Regex("""^[A-Za-z0-9]+_\d+:"""), "") to m.groupValues[2]
            }.toMap()
        } finally {
            Files.deleteIfExists(out)
            work.toFile().deleteRecursively()
        }
    }

    @Test
    @DisplayName("the three active-resource models agree exactly on every quantity they share")
    fun activeResourceVariantsAgree() {
        val pkg = "ksl.examples.general.models.wsc2025"
        val bq = quantities("$pkg.MM1ViaActiveResourceViaBQ")
        val hq = quantities("$pkg.MM1ViaActiveResourceViaHQ")
        val signal = quantities("$pkg.MM1ViaActiveResourceViaSignal")
        val shared = listOf("NumInSystem", "TimeInSystem", "NumBusy", "NumServed")
        for (name in shared) {
            val expected = bq.getValue(name)
            assertEquals(expected, hq.getValue(name), "hold queue: $name")
            assertEquals(expected, signal.getValue(name), "signal: $name")
        }
        // the server's waiting, kept under each model's own name
        assertEquals(hq.getValue("ServerWaitingQ:NumInQ"), signal.getValue("ServerWaitingQ:HoldQ:NumInQ"))
        assertEquals(bq.getValue("ServerInputQ:ChannelQ:NumInQ"), hq.getValue("CustomerWaitingQ:NumInQ"))
        assertEquals(hq.getValue("CustomerWaitingQ:NumInQ"), signal.getValue("CustomerQ:HoldQ:NumInQ"))
    }

    @Test
    @DisplayName("the walk-in health clinic gives the same results reneging by seizeOrTimeout as by an event and removeAndTerminate")
    fun clinicRenegingAgrees() {
        val original = quantities("ksl.examples.book.chapter7.WalkInHealthClinic")
        val timed = quantities("ksl.examples.families.WalkInHealthClinicViaTimedSeize")
        assertTrue(original.getValue("NumReneged").contains("avg=") && !original.getValue("NumReneged").contains("avg=0.0 "),
            "the clinic reneges: ${original.getValue("NumReneged")}")
        assertEquals(original, timed)
    }

    @Test
    @DisplayName("the STEM fair gives the same results with its capacities changed by a behavior as by a capacity schedule")
    fun stemFairScheduleAgrees() {
        val scheduled = quantities("ksl.examples.book.chapter7.StemFairMixerEnhancedSched")
        val byBehavior = quantities("ksl.examples.families.StemFairMixerEnhancedSchedViaBehavior")
        assertEquals(scheduled, byBehavior)
    }
}
