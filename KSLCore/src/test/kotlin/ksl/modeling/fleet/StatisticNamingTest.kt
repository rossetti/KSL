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
package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.GuidedPathTransportSystem
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.GuidedTransporterPoolWithQ
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  What the report looks like when **both** subsystems are in one model, which is the case nobody
 *  designed for and the one a paradigm comparison is made in.
 *
 *  Each subsystem's names are internally consistent; the awkwardness only arises together. Building
 *  the combined model and reading its seventy-one reported rows turned up three kinds of difference,
 *  and only one of them was a defect.
 *
 *  ## The one that was a defect
 *
 *  Both systems reported a row called `TransportTime`, at the same depth, meaning different things:
 *  request to set-down on the passive side -- the whole story, including the wait for a cart -- and
 *  aboard to set-down on the active side, which is a strict sub-interval of it. A comparison study
 *  lines rows up by name, so it would have compared them and got an answer that was wrong rather
 *  than obviously missing. The active row is now `TimeAboard`, named for the interval instead of for
 *  the journey.
 *
 *  The guard is the first test below, and it is deliberately structural rather than a list: **the two
 *  subsystems' own rows must have disjoint names.** That encodes the rule the space-layer extraction
 *  established -- a quantity both paradigms report belongs to the shared layer, not to each of them
 *  separately -- so a future row that breaks it fails here rather than in somebody's study.
 *
 *  Note what the existing `StatisticParityTest` asserted about this and why it did not catch it: it
 *  checked that the *full* name `Agv:TransportTime` was absent from a passive model whose system is
 *  called `Sys`, which is true for the wrong reason. Full names differ whenever the elements do; only
 *  leaf names can collide.
 *
 *  ## The two that were not
 *
 *  **The `:Space:` segment.** Thirteen rows the passive system reports at `Sys:NumZoneTraversals` the
 *  active one reports at `Agv:Space:NumZoneTraversals`. That is not an inconsistency to be fixed but
 *  a fact to be read: a passive transport system *is* a space, so its space rows sit at its own
 *  level, while an active system *has* one, so they sit under it. The mapping is one segment applied
 *  mechanically, which the second test asserts over the whole set rather than over a hand-kept list.
 *
 *  **The vocabulary.** The shared layer says transporter and the active subsystem says vehicle, so an
 *  active model reports both `Agv:Space:NumTransportersIdle` and `Agv:NumVehiclesIdle`. They are
 *  genuinely different quantities -- standing still, against carrying no task, which a repositioning
 *  vehicle satisfies one of and not the other -- and each word matches the type it reports on
 *  (`GuidedTransporter`, `AgvVehicle`). Renaming either would make the shared layer speak one
 *  consumer's dialect. What was missing was the documentation saying so, which is now on both
 *  properties.
 */
class StatisticNamingTest {

    private companion object {
        const val PASSIVE = "Manual"
        const val ACTIVE = "Auto"

        fun loop(name: String): GuidedPathNetwork = GuidedPathNetwork.builder(name)
            .link("Out", "A", "B", length = 100.0, zoneLength = 20.0, beginDirection = 0.0)
            .link("Back", "B", "A", length = 100.0, zoneLength = 20.0, beginDirection = 180.0)
            .build()
    }

    /**
     *  One model, both paradigms. A network belongs to one running system, so there are two of
     *  those -- which is itself part of what a combined study has to arrange.
     */
    private class BothParadigms(parent: ModelElement) : ProcessModel(parent, "Shop") {
        val passiveNetwork = loop("PassiveNet")
        val activeNetwork = loop("ActiveNet")

        init {
            spatialModel = passiveNetwork
        }

        val system = GuidedPathTransportSystem(this, passiveNetwork, name = PASSIVE)
        val cart = GuidedTransporter(
            system, TransporterPlacement.At("A"), ConstantRV(10.0), name = "Cart1"
        )
        val pool = GuidedTransporterPoolWithQ(this, system, listOf(cart), name = "Fleet")

        val agv = AgvSystem(this, activeNetwork, name = ACTIVE)
        val vehicle = AgvVehicle(agv, TransporterPlacement.At("A"), ConstantRV(10.0), name = "Cart2")
    }

    /** Every row that reaches the standard report, which is not every response registered. */
    private fun reportedRows(): Set<String> {
        val m = Model("Combined")
        BothParadigms(m)
        return (m.responses.filter { it.defaultReportingOption }.map { it.name } +
                m.counters.filter { it.defaultReportingOption }.map { it.name }).toSet()
    }

    /** The rows an element reports itself, as opposed to those its children report. */
    private fun ownRows(rows: Set<String>, element: String): Set<String> =
        rows.mapNotNull { row ->
            val marker = "$element:"
            val at = row.indexOf(marker)
            if (at < 0) null else row.substring(at + marker.length).takeIf { ':' !in it }
        }.toSet()

    // ---- the guard ------------------------------------------------------------------------------

    @Test
    @DisplayName("the two subsystems' own rows have disjoint names, so no name means two things")
    fun theTwoSubsystemsDoNotShareRowNames() {
        val rows = reportedRows()
        val passiveOwn = ownRows(rows, PASSIVE)
        val activeOwn = ownRows(rows, ACTIVE)

        assertTrue(passiveOwn.isNotEmpty() && activeOwn.isNotEmpty(), "the combined model reported nothing")

        val shared = passiveOwn intersect activeOwn
        assertEquals(
            emptySet(), shared,
            "these row names are reported by both subsystems in one model: $shared. A quantity both " +
                    "paradigms report belongs to the space layer they share, where it appears once; a " +
                    "name carried by both of them separately is two different measurements wearing " +
                    "one label, and a comparison lining rows up by name would compare them."
        )
        // The row this rule was written for, pinned by name so the reason survives the diff.
        assertTrue("TransportTime" in passiveOwn, "the passive subsystem stopped reporting TransportTime")
        assertTrue("TimeAboard" in activeOwn, "the active subsystem's own interval is not called TimeAboard")
        assertTrue(
            "TransportTime" !in activeOwn,
            "the active subsystem reports TransportTime again, which the passive one already uses for " +
                    "a different interval: request to set-down, against aboard to set-down"
        )
    }

    // ---- the mapping ----------------------------------------------------------------------------

    @Test
    @DisplayName("every shared row is found by inserting one segment, not by a lookup table")
    fun theSpaceLayerMappingIsMechanical() {
        val rows = reportedRows()
        // What the passive system reports and the active system's space layer also reports: the
        // shared layer's rows, which is what both paradigms have in common by construction.
        val spaceRows = rows.filter { it.contains("$ACTIVE:Space:") }
            .map { it.substringAfter("$ACTIVE:Space:") }
            .filter { ':' !in it }
            .toSet()
        assertTrue(spaceRows.size >= 10, "too few space-layer rows to be checking anything: $spaceRows")

        val unmapped = spaceRows.filterNot { rows.any { row -> row.endsWith("$PASSIVE:$it") } }
        assertEquals(
            emptyList(), unmapped,
            "these rows the active model reports under its space layer have no passive counterpart " +
                    "at the system's own level, so the one-segment mapping a comparison relies on is " +
                    "no longer mechanical: $unmapped"
        )

        // And the other direction, minus the passive paradigm's own figure, which by the test above
        // is the only thing the passive system reports that is not the space layer's.
        val passiveOwn = ownRows(rows, PASSIVE) - "TransportTime"
        assertEquals(
            emptySet(), passiveOwn - spaceRows,
            "the passive system reports rows that are neither the space layer's nor its own transport " +
                    "time, so the split between shared and paradigm-specific has drifted"
        )
    }

    // ---- the vocabulary is distinct on purpose --------------------------------------------------

    @Test
    @DisplayName("an active model reports two kinds of idleness, and they are different questions")
    fun idlenessMeansTwoThingsAndSaysWhich() {
        val rows = reportedRows()
        // Not a collision -- the words differ -- but the closest thing to one that remains, and the
        // reason both properties now carry documentation saying which question they answer.
        assertTrue(
            rows.any { it == "$ACTIVE:Space:NumTransportersIdle" },
            "the shared layer's physical idleness is missing: ${rows.filter { it.contains("Idle") }}"
        )
        assertTrue(
            rows.any { it == "$ACTIVE:NumVehiclesIdle" },
            "the active subsystem's dispatch idleness is missing: ${rows.filter { it.contains("Idle") }}"
        )
        // A vehicle repositioning is the case that separates them: it is moving, so not physically
        // idle, and carries no task, so idle to the dispatcher. Two rows, two questions.
        assertTrue(
            rows.any { it == "$ACTIVE:Space:NumTransportersMoving" } &&
                    rows.any { it == "$ACTIVE:NumVehiclesOnTask" },
            "the two rows that make the distinction observable are not both reported"
        )
    }
}
