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
package ksl.modeling.guidedpath

import ksl.modeling.guidedpath.internal.ZoneInvariantViolation
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  That the continuous audit actually runs, and that nothing can change the guide path without it.
 *
 *  These two tests exist because of what the alternative proves. The audit asserts properties whose
 *  violation has no other symptom, so a suite that passes is equally consistent with an audit that
 *  holds and an audit that never executes -- and the audit used to be driven by the executive's
 *  conditional-action sweep, which is a mechanism nothing else in the KSL uses and which it was
 *  abusing as a callback: its condition did all the work and then answered false so that its action
 *  would never be reached. Replacing that with a gate the guide path calls from its own work is only
 *  an improvement if the gate demonstrably fires, and if the list of places it is called from is
 *  demonstrably complete. One test per claim.
 */
class AuditGateTest {

    /** A straight path, two zones long per link, with one transporter that crosses it. */
    private class Corridor(parent: ModelElement) : ModelElement(parent, "Corridor") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Corridor")
            .link("L1", "A", "B", length = 24.0, zoneLength = 12.0)
            .link("L2", "B", "C", length = 24.0, zoneLength = 12.0)
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val cart = GuidedTransporter(
            system, TransporterPlacement.OnZone("L1.Zone1"), ConstantRV(10.0), 1, name = "Cart"
        )

        /** When to corrupt a zone behind the engine's back, or NaN to leave the run alone. */
        var corruptAt: Double = Double.NaN

        override fun initialize() {
            schedule({ _: KSLEvent<Nothing> -> cart.sendTo("C") }, 0.0)
            if (corruptAt.isFinite()) {
                // A zone the cart is nowhere near, given a holder that does not believe it holds
                // anything. No engine path can produce this; only a defect in the engine, or this
                // test, can.
                schedule({ _: KSLEvent<Nothing> ->
                    val victim = network.zone("L2.Zone2")!!
                    victim.state = ZoneState.COVERED
                    victim.holder = cart
                }, corruptAt)
            }
        }
    }

    private fun run(corruptAt: Double = Double.NaN): Corridor {
        val m = Model("AuditGate")
        val c = Corridor(m)
        c.corruptAt = corruptAt
        c.system.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0
        m.simulate()
        return c
    }

    @Test
    fun `a sound run is not disturbed by the audit`() {
        // The control. Without it, the test below would pass just as well against an audit that
        // threw on everything.
        val c = run()
        assertTrue(c.cart.frontZone != null, "the cart never got anywhere")
    }

    @Test
    fun `corrupting a zone mid-run is caught, and the report locates it`() {
        // 1.5 falls between the cart's traversals, which end at 1.2 and 2.4.
        val e = assertFailsWith<ZoneInvariantViolation> { run(corruptAt = 1.5) }
        val message = e.message ?: ""
        assertTrue(message.contains("L2.Zone2"), message)
        assertTrue(message.contains("does not count it among the zones it covers"), message)

        // Two times, and both are wanted. The audit asserts about the state left at the end of the
        // last instant the guide path took part in -- 1.2 -- and it makes that assertion at the top
        // of the next such instant, 2.4. In a real model the two are the same fact stated twice,
        // because nothing outside the guide path can change a zone, so state carrying a defect at
        // 2.4 was already carrying it at the end of 1.2.
        //
        // This test is the exception that proves the rule: it reaches in from a foreign event at
        // 1.5, so here the label is off by the width of one gap. That is the honest cost of a gate
        // driven by the subsystem's own work rather than by a sweep of the whole model, and it is
        // why the message names where it was found as well as what it is asserting about.
        assertTrue(message.contains("end of time 1.2"), message)
        assertTrue(message.contains("found at 2.4"), message)
    }

    @Test
    fun `nothing reaches the movement engine without passing the gate`() {
        // The completeness argument, held to the source rather than to a comment. Every mutator on
        // Zone and GuidedTransporter is internal to this package and every call to one is in
        // MovementEngine or GuidedTransporter.placeAtInitialPosition -- so the audit covers the
        // whole subsystem exactly when every call into the engine from outside it is gated. That
        // list is short today and would stop being complete the moment somebody added to it, which
        // is precisely the kind of thing a comment does not prevent.
        val source = Path.of("src/main/kotlin/ksl/modeling/guidedpath/GuidedPathSpace.kt")
        assumeTrue(Files.isRegularFile(source), "source not present in this checkout")
        val lines = Files.readAllLines(source)

        val ungated = mutableListOf<String>()
        for ((i, line) in lines.withIndex()) {
            if (!Regex("""engine\.\w+\(""").containsMatchIn(line)) continue
            // The gate is within a few lines above: some call sites guard their argument with a
            // `require` first, and some explain themselves in a comment.
            val preceding = (maxOf(0, i - 6) until i).map { lines[it] }
            if (preceding.none { it.contains("auditFinishedInstant()") }) {
                ungated.add("line ${i + 1}: ${line.trim()}")
            }
        }
        assertTrue(
            ungated.isEmpty(),
            "these calls into the movement engine are not preceded by auditFinishedInstant(), so a " +
                    "model could change the guide path without the audit ever seeing it:\n" +
                    ungated.joinToString("\n")
        )
    }
}
