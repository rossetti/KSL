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
package ksl.examples.general.agv

import ksl.simulation.Model
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  One shop, modelled both ways, leaves the same work in flight when the horizon falls.
 *
 *  This started as a question about log levels. [TwoParadigmsExample]'s run ended with three `WARN`
 *  lines from the active model and none from the passive one, which read as though the active model
 *  had misbehaved -- in the one example whose whole point is that the two agree. It had not: the two
 *  paradigms were in identical physical states, and only one of them counted.
 *
 *  The counting is now symmetric, and this is what holds it symmetric. It is a stronger statement
 *  than the example's own printed comparison, which pairs deliveries and time in system: those are
 *  about the work that *finished*. Work still in hand at the horizon is the other half, and a
 *  paradigm that quietly stranded an extra load would pass the example's comparison and fail here.
 *
 *  The correspondence is checked term for term rather than only in total, because the terms mean
 *  different things and a study acting on the wrong one changes the wrong thing:
 *
 *  | passive | active | what it says |
 *  |---|---|---|
 *  | `NumTransportsNeverStarted` | `NumTasksNeverAssigned` | the fleet never got to this work |
 *  | `NumTransportsUnfinished` | `NumAssignmentsStillOpen` | a journey was under way |
 *  | the two of them summed | `NumEntitiesNeverResumed` | both, together |
 *
 *  The passive side reports two rows and not three. Its total is deliberately not a row of its
 *  own: `StatisticNamingTest` holds the two subsystems' leaf names disjoint -- a quantity both
 *  paradigms report belongs to the layer they share, and a label carried by both separately is
 *  two measurements wearing one name -- and the fleet subsystem already owns
 *  `NumEntitiesNeverResumed`. Summing the two rows here is the whole cost of that rule.
 */
class WorkInFlightParityTest {

    private val replications = 20
    private val horizon = 8_000.0
    private val warmUp = 1_000.0

    private fun meanOf(m: Model, response: String): Double {
        val r = m.responses.firstOrNull { it.name == response }
            ?: error("model (${m.name}) has no response named '$response'")
        return r.acrossReplicationStatistic.average
    }

    @Test
    @DisplayName("the two paradigms leave the same work in flight, term for term")
    fun workInFlightAgrees() {
        val pm = Model("ParityPassive")
        val passive = PassiveShop(pm, name = "PassiveShop")
        pm.numberOfReplications = replications
        pm.lengthOfReplication = horizon
        pm.lengthOfReplicationWarmUp = warmUp
        pm.simulate()

        val am = Model("ParityActive")
        val active = ActiveShop(am, name = "ActiveShop")
        am.numberOfReplications = replications
        am.lengthOfReplication = horizon
        am.lengthOfReplicationWarmUp = warmUp
        am.simulate()

        // The premise: same world, same finished work. Without this the rest means nothing.
        assertEquals(
            passive.delivered.acrossReplicationStatistic.average,
            active.delivered.acrossReplicationStatistic.average,
            0.0,
            "the two shops must deliver identically, or they are not the same world"
        )

        val neverStarted = meanOf(pm, "Space:NumTransportsNeverStarted")
        val unfinished = meanOf(pm, "Space:NumTransportsUnfinished")
        val neverResumed = neverStarted + unfinished

        assertEquals(
            meanOf(am, "Agv:NumTasksNeverAssigned"), neverStarted, 0.0,
            "a load still waiting for a cart is the same condition as a task never assigned"
        )
        assertEquals(
            meanOf(am, "Agv:NumAssignmentsStillOpen"), unfinished, 0.0,
            "a journey under way is the same condition as an assignment left open"
        )
        assertEquals(
            meanOf(am, "Agv:NumEntitiesNeverResumed"), neverResumed, 0.0,
            "and the totals must agree, since the terms do"
        )

        // Non-vacuity: a horizon this model drained would make every assertion above trivially
        // true.
        assertTrue(
            neverResumed > 0.0,
            "this horizon is supposed to cut the run off mid-work; if it no longer does, the " +
                    "parity above is being asserted over zeroes and proves nothing"
        )
    }
}
