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
package ksl.modeling.agent

import ksl.modeling.entity.ProcessModel
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  **A movable agent resource starts every replication where it was declared.**
 *
 *  It did not. The position was set once, in an `init` block that runs at construction, and nothing
 *  put it back: `Context.initialize` restores *membership* between replications but never restored a
 *  *position*. So replication 1 started at the declared point and every replication after it started
 *  wherever replication 1 happened to end.
 *
 *  Measured before the fix, three replications with the cart declared at (0,0) and moved to (50,50)
 *  during each run:
 *
 *  ```
 *  start of replication 1: (0,0)      <- correct
 *  start of replication 2: (50,50)    <- wrong
 *  start of replication 3: (50,50)    <- wrong
 *  ```
 *
 *  **Why it went unseen, and why this test has the shape it has.** Nothing raises and nothing looks
 *  odd: the run completes, every statistic is computed, and the report is plausible. It is only
 *  wrong, and only in models whose answer depends on where vehicles begin -- which is every fleet
 *  model, since a dispatcher scoring by distance would score the second replication against
 *  positions carried over from the first.
 *
 *  It is invisible to any single-replication test, which is the whole lesson. This is the third
 *  member of a family: a guide path's transporter placement is re-applied every replication, a
 *  `FreePathBody`'s manifest is cleared every replication (found by the first study that ran three
 *  replications with loads still aboard), and this. **State that a replication must forget needs a
 *  `ModelElement` that forgets it, and a test that runs more than once.**
 */
class MovableAgentReplicationResetTest {

    private class Floor(parent: ModelElement) : ProcessModel(parent, "Floor") {

        val agents = Agents(this)

        /** Where each replication saw the cart at its very first instant. */
        val startingPositions = mutableListOf<Point2D>()

        override fun initialize() {
            startingPositions.add(agents.cart.position)
            // Move it well away from where it was declared, so a replication that failed to put it
            // back would be unmistakable rather than marginal.
            agents.cart.placeAt(Point2D(50.0, 50.0))
        }
    }

    private class Agents(parent: ModelElement) : AgentModel(parent, "Agents") {

        val context: Context<AgentLike> = Context("movers")

        val projection: ContinuousProjection<AgentLike> =
            ContinuousProjection(context, xRange = 0.0..100.0, yRange = 0.0..100.0)

        val cart: MovableAgentResource = MovableAgentResource(
            this, projection, Point2D(0.0, 0.0), name = "Cart", velocity = 10.0
        )
    }

    @Test
    @DisplayName("every replication starts the resource at its declared position")
    fun everyReplicationStartsWhereItWasDeclared() {
        val m = Model("ResetProbe")
        val floor = Floor(m)
        m.numberOfReplications = 3
        m.lengthOfReplication = 10.0
        m.simulate()

        assertEquals(3, floor.startingPositions.size, "three replications should each be observed")
        for ((i, p) in floor.startingPositions.withIndex()) {
            assertEquals(
                Point2D(0.0, 0.0), p,
                "replication ${i + 1} began at $p rather than at the declared position; a " +
                        "replication must not inherit where the previous one left the vehicle"
            )
        }
    }

    @Test
    @DisplayName("the resource is still a member of its context in later replications")
    fun membershipSurvives() {
        val m = Model("MembershipProbe")
        val floor = Floor(m)
        m.numberOfReplications = 3
        m.lengthOfReplication = 10.0
        m.simulate()

        // Re-placing must not have cost it its membership: a resource added during model
        // construction is setup scaffolding and stays a member for the whole study.
        assertTrue(
            floor.agents.cart in floor.agents.context,
            "the resource should remain a context member across replications"
        )
        assertEquals(
            1, floor.agents.context.size,
            "re-placing should not add a second copy of the resource to the context"
        )
    }
}
