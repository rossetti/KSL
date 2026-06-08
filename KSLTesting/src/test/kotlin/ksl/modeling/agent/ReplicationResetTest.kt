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

package ksl.modeling.agent

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 *  Regression tests for the per-replication reset fix (audit blocker
 *  B1). Verifies that:
 *   - runtime context membership and the associated projection state
 *     do NOT accumulate across replications,
 *   - setup-time (construction) membership and its projection state
 *     persist across replications,
 *   - a permanent agent's mailbox does not carry undelivered traffic
 *     from one replication into the next.
 */
class ReplicationResetTest {

    /**
     *  Adds one setup-time "anchor" agent at construction, then five
     *  runtime agents in every replication's `initialize()`. Records
     *  the context / grid sizes observed at the *start* of each
     *  replication (after the child `Context` has reset, before this
     *  model repopulates).
     */
    private class ContextResetModel(parent: ModelElement) : AgentModel(parent, "ctx-reset-am") {
        val world: Context<Agent> = Context("world")
        val grid: GridProjection<Agent> = GridProjection(world, columns = 10, rows = 10)

        inner class Walker(aName: String) : Agent(aName)

        val anchor = Walker("anchor")

        val ctxSizeAtStart = mutableListOf<Int>()
        val gridSizeAtStart = mutableListOf<Int>()
        val anchorPresentEachRep = mutableListOf<Boolean>()
        private var rep = 0

        init {
            world.add(anchor)            // setup-time membership
            grid.placeAt(anchor, 0, 0)   // setup-time projection state
        }

        override fun initialize() {
            // The Context (a child) has already reset by now: only the
            // setup membership should remain.
            ctxSizeAtStart.add(world.size)
            gridSizeAtStart.add(grid.size)
            anchorPresentEachRep.add(anchor in world && grid.cellOf(anchor) == Cell(0, 0))
            super.initialize()
            rep++
            repeat(5) { i ->
                val w = Walker("r$rep-$i")
                world.add(w)
                grid.placeAt(w, i + 1, i + 1)
            }
        }
    }

    @Test
    fun runtimeMembershipDoesNotAccumulateAcrossReplications() {
        val model = Model("CtxResetTest")
        val m = ContextResetModel(model)
        model.numberOfReplications = 3
        model.lengthOfReplication = 10.0
        model.simulate()

        // With the fix, every replication starts with only the anchor.
        assertEquals(listOf(1, 1, 1), m.ctxSizeAtStart, "context membership leaked across replications")
        assertEquals(listOf(1, 1, 1), m.gridSizeAtStart, "grid projection retained stale agents")
    }

    @Test
    fun setupMembershipAndProjectionStatePersist() {
        val model = Model("CtxAnchorTest")
        val m = ContextResetModel(model)
        model.numberOfReplications = 3
        model.lengthOfReplication = 10.0
        model.simulate()

        assertEquals(3, m.anchorPresentEachRep.size)
        assertTrue(
            m.anchorPresentEachRep.all { it },
            "the setup-time anchor and its grid placement must survive every replication",
        )
    }

    /**
     *  A [PermanentAgent] whose mailbox receives one message per
     *  replication (and never consumes it) must show exactly one
     *  pending message at the end of every replication — not an
     *  accumulating count.
     */
    private class MailboxResetModel(parent: ModelElement) : AgentModel(parent, "mbx-reset-am") {
        inner class Sender(aName: String) : Agent(aName)
        inner class Mailman(aName: String) : PermanentAgent(aName)

        val sender = Sender("sender")
        val pa = Mailman("pa")

        val pendingAtEnd = mutableListOf<Int>()

        override fun initialize() {
            super.initialize()
            // pa (a child) has already reset its mailbox by now. Deliver
            // exactly one fresh message this replication.
            pa.mailbox.deliver(AgentMessage.Inform(from = sender, payload = "tick"))
        }

        override fun afterReplication() {
            super.afterReplication()
            pendingAtEnd.add(pa.mailbox.size)
        }
    }

    @Test
    fun mailboxDoesNotCarryTrafficAcrossReplications() {
        val model = Model("MbxResetTest")
        val m = MailboxResetModel(model)
        model.numberOfReplications = 3
        model.lengthOfReplication = 10.0
        model.simulate()

        // One delivered, none consumed, per replication: a clean reset
        // gives [1, 1, 1]. Without the reset it would be [1, 2, 3].
        assertEquals(listOf(1, 1, 1), m.pendingAtEnd, "mailbox traffic leaked across replications")
    }
}
