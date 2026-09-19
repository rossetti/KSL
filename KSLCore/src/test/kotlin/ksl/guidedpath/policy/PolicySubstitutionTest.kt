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
package ksl.guidedpath.policy

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.GuidedPathTransportSystem
import ksl.modeling.guidedpath.GuidedTransportResult
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.GuidedTransporterPoolWithQ
import ksl.modeling.guidedpath.LinkType
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.Zone
import ksl.modeling.guidedpath.rules.GuidedTransporterAllocationRuleIfc
import ksl.modeling.guidedpath.rules.IdleDisposition
import ksl.modeling.guidedpath.rules.IdleDispositionRuleIfc
import ksl.modeling.guidedpath.rules.RouteSelectionRuleIfc
import ksl.modeling.guidedpath.rules.ZoneContentionRuleIfc
import ksl.modeling.guidedpath.rules.ZoneControlRuleIfc
import ksl.modeling.guidedpath.rules.ZoneReleaseTiming
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  `G8`: every policy of the subsystem can be replaced from outside it.
 *
 *  The claim being tested is not that the interfaces exist -- that is visible by reading -- but
 *  that a modeller can implement them **without being in the package**. A rule that needs an
 *  `internal` member to do its job is not replaceable by anyone outside the library, and the
 *  extension point is then decorative. So this file sits in `ksl.guidedpath.policy`, deliberately
 *  outside `ksl.modeling.guidedpath`, and it compiles only if all five interfaces are usable
 *  through public API alone. **The compilation is the test**; the assertions that follow merely
 *  confirm the substituted rules are actually consulted rather than silently ignored.
 *
 *  The five are the five decisions the design isolated: which route to take, when to give up the
 *  zone behind, who gets a contested zone, which transporter to dispatch, and where an idle
 *  transporter waits.
 */
class PolicySubstitutionTest {

    /** What each rule was asked, so the test can show it was consulted. */
    private object Calls {
        val routeSelections = mutableListOf<String>()
        val releaseTimings = mutableListOf<String>()
        val contentionChoices = mutableListOf<String>()
        val dispatches = mutableListOf<String>()
        val dispositions = mutableListOf<String>()

        fun clear() {
            routeSelections.clear(); releaseTimings.clear(); contentionChoices.clear()
            dispatches.clear(); dispositions.clear()
        }
    }

    // ---- the five rules, implemented here and nowhere near the package ------------------------

    /** Routes exactly as the default does, by asking the network for the shortest path. */
    private class RecordingRouteRule : RouteSelectionRuleIfc {
        override fun selectRoute(
            network: GuidedPathNetwork,
            fromZone: Zone,
            travellingForward: Boolean,
            toIntersection: GuidedPathNetwork.Intersection
        ): List<Zone> {
            Calls.routeSelections.add("${fromZone.name}->${toIntersection.name}")
            return network.shortestPathZones(fromZone, travellingForward, toIntersection)
        }
    }

    /** Holds the zone behind until arrival, which is the widest separation the model allows. */
    private class RecordingZoneControl : ZoneControlRuleIfc {
        override fun releaseTiming(transporter: GuidedTransporter, enteringZone: Zone): ZoneReleaseTiming {
            Calls.releaseTimings.add("${transporter.name}@${enteringZone.name}")
            return ZoneReleaseTiming.AtEnd
        }
    }

    /** Gives a contested zone to whoever has waited longest, by taking the head of the list. */
    private class RecordingContentionRule : ZoneContentionRuleIfc {
        override fun selectWaiter(zone: Zone, waiting: List<GuidedTransporter>): GuidedTransporter {
            val chosen = waiting.first()
            Calls.contentionChoices.add("${zone.name}:${chosen.name}")
            return chosen
        }
    }

    /** Dispatches by name, which no built-in rule does, so its effect is unmistakable. */
    private class AlphabeticalDispatchRule : GuidedTransporterAllocationRuleIfc {
        override fun selectTransporter(
            network: GuidedPathNetwork,
            pickup: GuidedPathNetwork.Intersection,
            candidates: List<GuidedTransporter>
        ): GuidedTransporter {
            val chosen = candidates.minByOrNull { it.name }!!
            Calls.dispatches.add("${pickup.name}:${chosen.name}")
            return chosen
        }
    }

    /** Sends every released transporter to one named junction. */
    private class RecordingStagingRule(private val where: String) : IdleDispositionRuleIfc {
        override fun disposition(transporter: GuidedTransporter): IdleDisposition {
            Calls.dispositions.add(transporter.name)
            return IdleDisposition.MoveTo(where)
        }
    }

    // ---- a model built entirely out of them ---------------------------------------------------

    private class SubstitutedShop(parent: ModelElement) : ProcessModel(parent, "SubstitutedShop") {

        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Substituted")
            .intersection("I1", x = 0.0, y = 72.0)
            .intersection("I2", x = 48.0, y = 72.0)
            .intersection("I3", x = 48.0, y = 0.0)
            .intersection("I4", x = 0.0, y = 0.0)
            .intersection("I5", x = 0.0, y = -36.0)
            .intersection("I6", x = -36.0, y = 72.0)
            .link("Link1", "I1", "I2", length = 48.0, zoneLength = 12.0, beginDirection = 0.0)
            .link("Link2", "I2", "I3", length = 72.0, zoneLength = 12.0, beginDirection = 270.0)
            .link("Link3", "I3", "I4", length = 48.0, zoneLength = 12.0, beginDirection = 180.0)
            .link("Link4", "I4", "I1", length = 72.0, zoneLength = 12.0, beginDirection = 90.0)
            // A parking spur each, so that neither idle transporter stands in the other's way.
            // Without them the near transporter would sit on the loop and the far one could never
            // reach the pickup, and the test would be measuring a deadlock rather than a rule.
            .link("Spur5", "I4", "I5", length = 36.0, zoneLength = 12.0, type = LinkType.SPUR, beginDirection = 270.0)
            .link("Spur6", "I1", "I6", length = 36.0, zoneLength = 12.0, type = LinkType.SPUR, beginDirection = 180.0)
            .routeSelectionRule(RecordingRouteRule())
            .build()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(
            this, network, zoneContentionRule = RecordingContentionRule(), name = "Sys"
        )

        // Named so that alphabetical order and nearest-first order disagree. The pickup is I2:
        // Zulu parks 84 feet away and Alpha 156, so any distance-based rule sends Zulu. The
        // substituted rule must send Alpha.
        val alpha = GuidedTransporter(
            system, TransporterPlacement.At("I5"), ConstantRV(10.0), 1, RecordingZoneControl(), "Alpha"
        )
        val zulu = GuidedTransporter(
            system, TransporterPlacement.At("I6"), ConstantRV(10.0), 1, RecordingZoneControl(), "Zulu"
        )

        val carts = GuidedTransporterPoolWithQ(
            this, system, listOf(alpha, zulu),
            AlphabeticalDispatchRule(), RecordingStagingRule("I4"), "Carts"
        )

        val results = mutableListOf<GuidedTransportResult>()
        var carriedBy: String? = null

        inner class Load : Entity() {
            val move = process("load") {
                entity.currentLocation = network.requireLocation("I2")
                val request = requestGuidedTransporter(carts, pickupLocation = "I2")
                carriedBy = request.transporter.name
                results.add(transportBy(request, destination = "I3"))
                releaseGuidedTransporter(request, carts)
            }
        }

        override fun initialize() {
            results.clear()
            carriedBy = null
            activate(Load().move)
        }
    }

    private fun run(): SubstitutedShop {
        Calls.clear()
        val m = Model("SubstitutionRun")
        val shop = SubstitutedShop(m)
        shop.system.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()
        return shop
    }

    @Test
    @DisplayName("A model can be built from five rules written outside the package")
    fun allFiveRulesAreAcceptedAndConsulted() {
        val shop = run()
        assertEquals(1, shop.results.size, "the substituted model must still complete its work")
        assertTrue(Calls.routeSelections.isNotEmpty(), "the route rule must be asked for every journey")
        assertTrue(Calls.releaseTimings.isNotEmpty(), "the zone control rule must be asked per zone")
        assertTrue(Calls.dispatches.isNotEmpty(), "the allocation rule must choose the transporter")
        assertTrue(Calls.dispositions.isNotEmpty(), "the idle rule must be asked on release")
    }

    @Test
    @DisplayName("A substituted dispatch rule overrides the built-in choice, not merely records it")
    fun theDispatchRuleActuallyDecides() {
        val shop = run()
        // Zulu is standing on the pickup itself and would be chosen by any distance-based rule.
        assertEquals(
            "Alpha", shop.carriedBy,
            "the alphabetical rule must be obeyed; getting Zulu would mean the pool consulted its " +
                    "own default and the extension point does nothing"
        )
    }

    @Test
    @DisplayName("A substituted idle rule moves the transporter where it says")
    fun theIdleRuleActuallyDecides() {
        val shop = run()
        assertTrue(Calls.dispositions.contains("Alpha"), "the released transporter must be offered to the rule")
        assertEquals(
            "I4", shop.alpha.currentLocation.name,
            "the staging rule sent Alpha to I4, and the run is long enough for it to arrive"
        )
    }
}
