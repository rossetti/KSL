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

import ksl.modeling.fleet.policies.BatchedAssignmentPolicy
import ksl.modeling.fleet.policies.ConsolidatingPolicy
import ksl.modeling.fleet.policies.LeastUsedVehiclePolicy
import ksl.modeling.fleet.policies.ParkInPlaceDisposition
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.LinkType
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.spatial.DistancesModel
import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  **Gate 6: one fleet, two substrates, swept over vehicle capacity.**
 *
 *  The same dispatcher, the same tour policy, the same consolidation, the same demand and the same
 *  distances — run once over a guide path and once over a free path. **Everything above the
 *  substrate is literally the same code**; the fixtures below differ only in which `FleetSystem`
 *  and which `FleetVehicle` they build, which is what seven phases of seam work were for.
 *
 *  ### What the experiment isolates
 *
 *  A free-path model makes one assertion a guide path does not: **vehicles do not get in each
 *  other's way**. To measure only that, everything else is held equal:
 *
 *  - The free path's distances are the guide path's own link lengths, taken **directionally**, so a
 *    cart going the wrong way round a one-way loop pays the same on both. The free path is not
 *    allowed to travel as the crow flies, which would confound geometry with interference.
 *  - The assignment rule is [LeastUsedVehiclePolicy], which does not read a vehicle's position.
 *    A positional rule would confound the comparison: a `DistancesModel` cannot say where between
 *    two places is, so its vehicles' positions are stale mid-journey while a guide path's are live,
 *    and the two would be making *different decisions* rather than the same decisions in different
 *    worlds.
 *
 *  ### What is asserted, and what is only reported
 *
 *  The **law** is that a free-path model cannot be pessimistic: same distances, same decisions, and
 *  nothing to wait for, so no journey can take longer. That is asserted.
 *
 *  The **question** — does the optimism shrink as capacity rises, because consolidation puts fewer
 *  vehicles on the floor for the same work? — is reported rather than asserted. Phase 3 taught this
 *  subsystem the difference the hard way: a direction that sounds inevitable is a fact about a
 *  regime, and asserting it turns a study into a fixture.
 */
class SubstrateParityTest {

    private companion object {
        const val ENTRY = "Entry"
        const val A = "A"
        const val B = "B"
        const val VELOCITY = 10.0
        const val TIME_BETWEEN_ARRIVALS = 5.0
        const val HORIZON = 4000.0

        /** Long enough that several loads are waiting when a pass runs, so a vehicle with room
         *  has something to fill it with. Without one, consolidation has nothing to consolidate
         *  except in saturation -- where nothing else is measurable either. */
        const val BATCH_WINDOW = 5.0
        const val CARTS = 8

        // A one-way loop: Entry -> A -> B -> Entry, plus a parking spur per cart off Entry so that
        // an idle cart is not standing in the aisle. Going backwards means going all the way round,
        // which is what makes a one-way loop worth modelling at all.
        const val ENTRY_TO_A = 100.0
        const val A_TO_B = 100.0
        const val B_TO_ENTRY = 100.0
        const val HOME_TO_ENTRY = 20.0
    }

    /** What one configuration produced. */
    private data class Outcome(
        val completions: Double,
        val timeInSystem: Double,
        val blockedFraction: Double
    )

    // ---- the guide path ------------------------------------------------------------------------

    private class GuidedShop(parent: ModelElement, capacity: Int) : ProcessModel(parent, "GuidedShop") {

        val network: GuidedPathNetwork = run {
            var b = GuidedPathNetwork.builder("Loop")
                .intersection("I1", x = 0.0, y = 0.0)
                .intersection("I2", x = 100.0, y = 0.0)
                .intersection("I3", x = 100.0, y = 100.0)
                .link("L1", "I1", "I2", length = ENTRY_TO_A, zoneLength = 25.0, beginDirection = 0.0)
                .link("L2", "I2", "I3", length = A_TO_B, zoneLength = 25.0, beginDirection = 90.0)
                .link("L3", "I3", "I1", length = B_TO_ENTRY, zoneLength = 25.0, beginDirection = 225.0)
            for (i in 1..CARTS) {
                b = b.intersection("H$i", x = -20.0, y = 20.0 * i)
                b = b.link(
                    "HS$i", "I1", "H$i", length = HOME_TO_ENTRY, zoneLength = 20.0,
                    type = LinkType.SPUR, beginDirection = 180.0
                )
            }
            b.station(ENTRY, "I1").station(A, "I2").station(B, "I3").build()
        }

        init {
            spatialModel = network
        }

        val fleet = AgvSystem(
            this, network,
            assignmentPolicy = BatchedAssignmentPolicy(
                window = BATCH_WINDOW, inner = ConsolidatingPolicy(LeastUsedVehiclePolicy())
            ),
            name = "Fleet"
        )

        val carts = (1..CARTS).map { i ->
            AgvVehicle(
                fleet, TransporterPlacement.At("H$i"), ConstantRV(VELOCITY),
                name = "Cart$i", loadCapacity = capacity
            ).apply {
                homeBase = "H$i"
                dispositionPolicy = ksl.modeling.fleet.policies.ReturnToHomeBaseDisposition()
            }
        }

        val timeInSystem = Response(this, "TimeInSystem")
        val completed = Counter(this, "Completed")
        private var made = 0

        @Suppress("unused")
        private val generator = EntityGenerator(
            ::Part, ConstantRV(TIME_BETWEEN_ARRIVALS), ConstantRV(TIME_BETWEEN_ARRIVALS)
        )

        inner class Part : Entity() {
            private val target = if (made++ % 2 == 0) A else B

            @Suppress("unused")
            val haul = process(isDefaultProcess = true) {
                val arrived = time
                currentLocation = network.requireLocation(ENTRY)
                transportByFleet(fleet, target, origin = ENTRY)
                timeInSystem.value = time - arrived
                completed.increment()
            }
        }
    }

    // ---- the free path, over the same distances -------------------------------------------------

    private class FreeShop(parent: ModelElement, capacity: Int) : ProcessModel(parent, "FreeShop") {

        private val distances = DistancesModel()

        init {
            // Directional, and matching the loop exactly: forward is one leg, backward is the whole
            // way round. The free path is not allowed to cut across, which would make this a
            // comparison of geometries rather than of interference.
            val loop = ENTRY_TO_A + A_TO_B + B_TO_ENTRY
            distances.addDistance(ENTRY, A, ENTRY_TO_A)
            distances.addDistance(A, B, A_TO_B)
            distances.addDistance(B, ENTRY, B_TO_ENTRY)
            distances.addDistance(A, ENTRY, loop - ENTRY_TO_A)
            distances.addDistance(B, A, loop - A_TO_B)
            distances.addDistance(ENTRY, B, ENTRY_TO_A + A_TO_B)
            for (i in 1..CARTS) {
                distances.addDistance("H$i", ENTRY, HOME_TO_ENTRY)
                distances.addDistance(ENTRY, "H$i", HOME_TO_ENTRY)
                distances.addDistance(A, "H$i", loop - ENTRY_TO_A + HOME_TO_ENTRY)
                distances.addDistance(B, "H$i", B_TO_ENTRY + HOME_TO_ENTRY)
                distances.addDistance("H$i", A, HOME_TO_ENTRY + ENTRY_TO_A)
                distances.addDistance("H$i", B, HOME_TO_ENTRY + ENTRY_TO_A + A_TO_B)
            }
            distances.defaultVelocity = ConstantRV(VELOCITY)
            spatialModel = distances
        }

        val fleet = FreePathFleet(
            this, distances,
            assignmentPolicy = BatchedAssignmentPolicy(
                window = BATCH_WINDOW, inner = ConsolidatingPolicy(LeastUsedVehiclePolicy())
            ),
            name = "Fleet"
        )

        val carts = (1..CARTS).map { i ->
            FreePathVehicle(
                fleet, "H$i", ConstantRV(VELOCITY), name = "Cart$i", loadCapacity = capacity
            ).apply {
                homeBase = "H$i"
                dispositionPolicy = ksl.modeling.fleet.policies.ReturnToHomeBaseDisposition()
            }
        }

        val timeInSystem = Response(this, "TimeInSystem")
        val completed = Counter(this, "Completed")
        private var made = 0

        @Suppress("unused")
        private val generator = EntityGenerator(
            ::Part, ConstantRV(TIME_BETWEEN_ARRIVALS), ConstantRV(TIME_BETWEEN_ARRIVALS)
        )

        inner class Part : Entity() {
            private val target = if (made++ % 2 == 0) A else B

            @Suppress("unused")
            val haul = process(isDefaultProcess = true) {
                val arrived = time
                currentLocation = fleet.space.requireLocation(ENTRY)
                transportByFleet(fleet, target, origin = ENTRY)
                timeInSystem.value = time - arrived
                completed.increment()
            }
        }
    }

    private fun guided(capacity: Int): Outcome {
        val m = Model("GuidedParity$capacity")
        val shop = GuidedShop(m, capacity)
        m.numberOfReplications = 3
        m.lengthOfReplication = HORIZON
        m.simulate()
        return Outcome(
            shop.completed.acrossReplicationStatistic.average,
            shop.timeInSystem.acrossReplicationStatistic.average,
            shop.carts.map { it.fracTimeBlocked.acrossReplicationStatistic.average }.average()
        )
    }

    private fun free(capacity: Int): Outcome {
        val m = Model("FreeParity$capacity")
        val shop = FreeShop(m, capacity)
        m.numberOfReplications = 3
        m.lengthOfReplication = HORIZON
        m.simulate()
        return Outcome(
            shop.completed.acrossReplicationStatistic.average,
            shop.timeInSystem.acrossReplicationStatistic.average,
            shop.carts.map { it.fracTimeBlocked.acrossReplicationStatistic.average }.average()
        )
    }

    @Test
    @DisplayName("One fleet over two substrates: what a free-path model does not see")
    fun theOptimismOfAFreePath() {
        val rows = StringBuilder()
        rows.append(
            "\n  capacity | completions g/f | time in system g/f | blocked (guide) | optimism\n"
        )
        for (capacity in 1..3) {
            val g = guided(capacity)
            val f = free(capacity)

            // Both substrates ran the same work with the same fleet.
            assertTrue(g.completions > 0.0, "the guided fleet delivered nothing at capacity $capacity")
            assertTrue(f.completions > 0.0, "the free fleet delivered nothing at capacity $capacity")

            // The free path asserts vehicles do not obstruct one another, and reports it.
            assertEquals(
                0.0, f.blockedFraction,
                "a free-path vehicle was blocked at capacity $capacity, which a free path says " +
                        "cannot happen"
            )

            // The guide path's vehicles do get in each other's way, which is the phenomenon under
            // study. A regime where they do not would make the comparison vacuous.
            assertTrue(
                g.blockedFraction > 0.05,
                "the guided fleet was blocked only ${g.blockedFraction} of the time at capacity " +
                        "$capacity, so this regime has nothing to compare: pick a busier one"
            )

            // **The precondition of the comparison, and it is not a formality.** A mean time in
            // system is an average over the loads that were *served*. Where one fleet clears less
            // work than the other, the two means are over different populations and the faster
            // number can belong to the fleet that simply gave up on more of them -- which is what
            // saturating this fixture does, and what an earlier draft of it measured without
            // noticing.
            val throughputGap = kotlin.math.abs(g.completions - f.completions) /
                    maxOf(g.completions, f.completions)
            assertTrue(
                throughputGap < 0.02,
                "the two fleets cleared different amounts of work at capacity $capacity " +
                        "(${g.completions} against ${f.completions}), so their mean times in " +
                        "system are averages over different populations and must not be compared"
            )

            // Given that, the free path cannot be slower: same distances, same decisions, and
            // nothing to wait for.
            assertTrue(
                f.timeInSystem <= g.timeInSystem + 1e-9,
                "at capacity $capacity the free-path model was SLOWER (${f.timeInSystem}) than the " +
                        "guide path (${g.timeInSystem}) while clearing the same work. With the " +
                        "same distances and the same decisions, removing interference cannot cost " +
                        "time"
            )

            val gap = (g.timeInSystem - f.timeInSystem) / g.timeInSystem
            rows.append(
                "  %8d | %6.1f / %-6.1f | %7.2f / %-7.2f | %14.4f | %6.1f%%\n".format(
                    capacity, g.completions, f.completions, g.timeInSystem, f.timeInSystem,
                    g.blockedFraction, gap * 100.0
                )
            )
        }
        println(rows)
    }

    @Test
    @DisplayName("Capacity buys nothing on a fleet that is not the constraint, on either substrate")
    fun capacityBuysNothingWhereThereIsNothingToConsolidate() {
        // The other half of the question Gate 6 was posed to answer, and the answer is a fact about
        // a regime rather than a law. Consolidation fires when a dispatching pass has more work in
        // front of it than vehicles to give it to. This fixture has eight vehicles and a five-unit
        // batching window, so a pass almost never has a second load for a vehicle that has room --
        // and a bigger vehicle is therefore exactly as good as a smaller one.
        //
        // Phase 3 reached the same conclusion from the other side: whether capacity is worth
        // anything is a property of how loaded the fleet is, not of the capacity. Engineering this
        // fixture until capacity appeared to matter would have replaced a finding with a fixture.
        val byCapacity = (1..3).associateWith { guided(it) }
        val times = byCapacity.values.map { it.timeInSystem }.distinct()
        assertEquals(
            1, times.size,
            "capacity changed the outcome in a regime with more vehicles than simultaneous " +
                    "demand: $byCapacity. If that is now true, the finding above needs rewriting " +
                    "rather than this assertion relaxing"
        )
    }
}
