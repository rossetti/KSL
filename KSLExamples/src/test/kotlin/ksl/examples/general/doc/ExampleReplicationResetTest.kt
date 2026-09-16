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

import ksl.examples.general.guidedpath.CrossingArbiterExample
import ksl.examples.general.guidedpath.GuidePathDisturbancesExample
import ksl.examples.general.guidedpath.ZoneClosurePolicyExample
import ksl.simulation.Model
import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 *  Whether the guide-path examples put themselves back between replications.
 *
 *  This exists because of a hole in the gate rather than a hypothesis. The examples are held to
 *  their recorded output, and that catches a great deal -- but every one of those captures is a
 *  **single-replication** run, and state carried from one replication into the next is invisible to
 *  a check like that. A model that accumulates something on every replication produces exactly the
 *  same first replication, and therefore exactly the same fingerprint, for ever.
 *
 *  It was written after a real instance. `CrossingArbiterExample` attached its arrival listener
 *  inside `initialize()`, which is called once per replication and adds a listener that is never
 *  removed: replication *n* counted every arrival *n* times. The counter it fed **was** reset
 *  properly, which is what hid it, and the example runs one replication, which is why nothing said
 *  so. Every other place in the repository attaches such a listener in `init { }`.
 *
 *  Two checks, because the examples divide into two kinds.
 *
 *  **Deterministic examples must repeat exactly.** With no randomness, three replications of a
 *  model that resets properly are three identical replications, so every response and every counter
 *  must have an across-replication variance of exactly zero. That is a strong statement and it
 *  needs no knowledge of what any particular example does.
 *
 *  **Stochastic examples cannot be asked to repeat**, so they get the other check: run more than one
 *  replication with the invariant harness on. State that survived a reset shows up in replication
 *  two as a violated invariant -- a zone still held by a holder from the replication before, a count
 *  that did not return to zero -- and the harness raises rather than reporting a plausible number.
 */
class ExampleReplicationResetTest {

    private val replications = 3

    /** The horizons the two deterministic examples run over in their own `main` functions. */
    private val crossingHorizon = 120.0
    private val closurePolicyHorizon = 20.0

    /**
     *  Every response and counter in the model, checked for exact agreement across replications.
     *
     *  Written against the model's own lists rather than a hand-kept set of names, so an example
     *  that gains a statistic is covered without anybody remembering to add it here.
     */
    private fun assertEveryReplicationAgrees(model: Model) {
        val disagreed = mutableListOf<String>()
        for (r in model.responses) {
            val sd = r.acrossReplicationStatistic.standardDeviation
            if (sd > 1.0e-9) {
                disagreed.add(
                    "${r.name}: sd=$sd over ${r.acrossReplicationStatistic.count} replications"
                )
            }
        }
        for (c in model.counters) {
            val sd = c.acrossReplicationStatistic.standardDeviation
            if (sd > 1.0e-9) {
                disagreed.add(
                    "${c.name}: sd=$sd over ${c.acrossReplicationStatistic.count} replications"
                )
            }
        }
        if (disagreed.isNotEmpty()) {
            fail(
                "a deterministic model's replications must be identical, and these differ:\n" +
                        disagreed.joinToString("\n") { "  $it" } +
                        "\nSomething was not put back at the start of a replication. Look for state " +
                        "set up in initialize() that should have been set up in init, and for lists, " +
                        "maps and counters that are never cleared."
            )
        }
    }

    @Test
    @DisplayName("The crossing example repeats exactly, and does not count an arrival twice")
    fun theCrossingExampleResets() {
        val m = Model("CrossingReset")
        val town = CrossingArbiterExample(m, "BoundedBatch", name = "Town")
        town.system.checkInvariants = true
        m.numberOfReplications = replications
        m.lengthOfReplication = crossingHorizon
        m.simulate()
        assertEveryReplicationAgrees(m)

        // The example's own bookkeeping is a plain Int rather than a response, so the check above
        // cannot see it -- and a plain Int is exactly where the defect that prompted this test
        // lived. One run of one replication says what the truth is; the last of three must match.
        val single = Model("CrossingSingle")
        val once = CrossingArbiterExample(single, "BoundedBatch", name = "Town")
        single.numberOfReplications = 1
        single.lengthOfReplication = crossingHorizon
        single.simulate()

        assertEquals(
            once.cartTrips, town.cartTrips,
            "cart trips after $replications replications must equal the single-replication count; " +
                    "a listener attached in initialize() rather than init would make this a multiple"
        )
        assertEquals(once.walkersAcross, town.walkersAcross, "and so must the walkers")
    }

    @Test
    @DisplayName("The closure-policy example repeats exactly under every policy")
    fun theClosurePolicyExampleResets() {
        for (policy in ZoneClosurePolicyExample.OverlapPolicy.entries) {
            val m = Model("PolicyReset_$policy")
            val aisle = ZoneClosurePolicyExample(m, policy, name = "Aisle")
            aisle.system.checkInvariants = true
            m.numberOfReplications = replications
            m.lengthOfReplication = closurePolicyHorizon
            m.simulate()
            assertEveryReplicationAgrees(m)

            // The timeline is a list on the element, and a list that is not cleared is the other
            // half of the mistake this test is about. Three replications must leave one
            // replication's worth of lines, not three.
            val single = Model("PolicySingle_$policy")
            val onceAisle = ZoneClosurePolicyExample(single, policy, name = "Aisle")
            single.numberOfReplications = 1
            single.lengthOfReplication = closurePolicyHorizon
            single.simulate()
            assertEquals(
                onceAisle.timeline.size, aisle.timeline.size,
                "under $policy the timeline must hold one replication's lines, not every one's"
            )
        }
    }

    @Test
    @DisplayName("The disturbances example survives repeated replications with invariants on")
    fun theDisturbancesExampleResets() {
        // Stochastic, so its replications are not meant to agree and the check above would be
        // false. The invariant harness is the check that does apply: space held by a holder from
        // the replication before, or a population that did not return to zero, is a violation in
        // replication two rather than a number nobody questions.
        val m = Model("DisturbancesReset")
        val shop = GuidePathDisturbancesExample(m, disturbed = true)
        shop.system.checkInvariants = true
        m.numberOfReplications = replications
        m.lengthOfReplication = 400.0
        m.lengthOfReplicationWarmUp = 0.0
        m.simulate()

        // And one thing that holds however random it is: every replication must have run to the
        // end and contributed an observation. A replication that died in teardown -- which is how
        // an unreset element usually fails -- contributes none.
        assertEquals(
            replications.toDouble(), shop.completed.acrossReplicationStatistic.count,
            "every replication must have completed and contributed an observation"
        )
        assertTrue(
            shop.completed.acrossReplicationStatistic.average > 0.0,
            "and must have delivered something, or the check above passes on an empty model"
        )
    }
}
