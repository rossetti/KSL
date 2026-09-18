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

package ksl.examples.general.guidedpath

import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.GuidedPathTransportSystem
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.Zone
import ksl.modeling.guidedpath.ZoneAllocation
import ksl.modeling.guidedpath.ZoneHoldActionIfc
import ksl.modeling.guidedpath.ZoneHolderIfc
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV

/*
 *  What to do when the guide-path space you asked for is already promised to somebody else.
 *
 *  A zone carries **one promise** at a time. Two closures may be in force at once and may even want
 *  the same zone, and by default the second asker is refused rather than queued behind the first --
 *  `ZoneOverlap.QUEUE` is what a model opts into when serialising them is what it means. So a model
 *  whose closures land where they land will sooner or later ask for space that is already promised.
 *  [ksl.modeling.guidedpath.GuidedPathSpace.tryRequestZones],
 *  [ksl.modeling.guidedpath.GuidedPathSpace.tryHoldZonesFor] and
 *  [ksl.modeling.entity.KSLProcessBuilder.trySeizeZones] answer **null** in that case instead of
 *  refusing the model, and **what to do about the null is the model's decision.**
 *
 *  This example exists to show that the decision is real, and it is arranged rather than sampled:
 *  everything below happens at a stated time, so every line of the output can be checked by hand.
 *  Three reasonable answers to one condition, on the same layout, giving three different outcomes.
 *
 *  ## The arrangement
 *
 *  One aisle of six twelve-foot zones, and one cart at twelve feet a minute, so **a zone is a
 *  minute** and the cart reaches `Zone`*k* at time *k*. Under end-of-zone control it gives a zone up
 *  on arriving in the next one, so it holds `Zone`*k* from *k* until *k+1*.
 *
 *  - **2.5** the maintenance crew asks for `Zone3` and `Zone4`. The cart claimed `Zone3` at 2.0 and
 *    is travelling into it, so the set has not drained: both zones are *promised* to the crew and
 *    nothing is held yet.
 *  - **2.6** a spill wants `Zone4` and `Zone5`. `Zone4` is promised, so the answer is null -- and
 *    what happens next is the policy.
 *  - **5.0** the cart clears `Zone4`, the crew's set has drained, and the crew takes it -- under
 *    the first two policies. Under the third the spill is in the cart's way and this slips to 5.6,
 *    which is the one line of the arrangement that the policy moves.
 *
 *  ## The three answers
 *
 *  - [OverlapPolicy.ABSORB] -- the spill is *part of* the closure already being set up. One spill,
 *    cleaned once, and the aisle is closed no longer than it would have been.
 *  - [OverlapPolicy.DEFER] -- ask again shortly. The retry at **5.6** *succeeds*, and the reason is
 *    the distinction this whole verb exists for: by then the crew **holds** `Zone4` rather than
 *    being promised it, and a held zone is not a collision. The spill's request is accepted and
 *    waits for the hold to end.
 *  - [OverlapPolicy.RELOCATE] -- do the same work elsewhere. `Zone5` and `Zone6` are free and
 *    unpromised at 2.6, so the spill is dealt with immediately and the two closures overlap in time
 *    without overlapping in space. The timeline shows what that costs: more of the aisle is shut at
 *    once, and the cart, which reaches `B` at 6.0 under the other two policies, is held up behind
 *    the relocated closure and reaches it at 6.6.
 *
 *    That 0.6 is the one figure not read straight off the arrangement, so here is where it comes
 *    from. End-of-zone control means a cart claims the zone ahead as it begins to leave the one it
 *    is in, so the cart claims `Zone5` at **4.0**, not on arriving there at 5.0. The relocated
 *    spill holds `Zone5` until 4.6. The cart therefore waits 0.6, and everything downstream of it
 *    moves by 0.6 as well: it reaches `B` at 6.6 rather than 6.0, and because it clears `Zone4`
 *    0.6 late the crew's set drains 0.6 late and the crew takes at 5.6 rather than 5.0.
 *
 *  None is more correct than the others. Which one a model wants is a statement about the thing
 *  being modelled -- spilled fluid spreads, an inspection waits, a work crew goes where it is needed
 *  -- and no library can read that off the geometry.
 *
 *  ## Promised is not held
 *
 *  Worth being exact about, because it is what a hand-written guard gets backwards. A zone is
 *  **promised** only between the instant it is asked for and the instant it has drained. Once the
 *  closure has actually **taken** the zone, the promise is gone and the zone is merely held -- and
 *  asking for a held zone succeeds and waits. A guard written as "is anything closing *or holding*
 *  this zone?" would turn away the DEFER retry at 5.6, which is a closure that works perfectly
 *  well.
 *
 *  ## The warning above the timelines
 *
 *  Every run prints this once per policy, and it is the layout rather than a fault:
 *
 *  ```
 *  WARN GuidedPathNetwork (Aisle): 1 ordered intersection pair(s) have no path.
 *       Requesting a route between one of them raises. First few: B -> A
 *  ```
 *
 *  The aisle is a single one-way link from `A` to `B`, so there is no way back and the network
 *  says so. On a layout meant to be a circuit that warning is worth acting on -- a one-way loop
 *  with a leg pointing the wrong way strands everything downstream of it. Here the aisle is the
 *  whole network and the cart makes one trip along it, so there is nothing to return to.
 *
 *  ## Both routes, one decision
 *
 *  The crew is event-scheduled and uses `tryHoldZonesFor`. The spill is an entity and uses the
 *  suspending `trySeizeZones`, which is why its policy reads as straight-line code -- a retry loop
 *  in a process is a `delay` inside a `while`, where the same thing on the event route has to be
 *  spread across an action that reschedules itself.
 */
/** A holder is a plain object: a name, and what it waits for, which for a closure is nothing. */
private class Crew(override val name: String) : ZoneHolderIfc {
    override val awaitedZone: Zone? get() = null
}

/**
 *  One aisle, one cart, one scheduled closure and one spill that collides with it.
 *
 *  @param parent the containing model element
 *  @param policy what the spill does when the space it wants is already promised
 */
class ZoneClosurePolicyExample(
    parent: ModelElement,
    val policy: OverlapPolicy,
    name: String? = null
) : ProcessModel(parent, name) {

    /** What a model does when the space it asked for is already promised to somebody else. */
    enum class OverlapPolicy {
        /** The new closure is part of the one already there. */
        ABSORB,

        /** Wait a little and ask again. */
        DEFER,

        /** Do the same work somewhere else. */
        RELOCATE
    }

    // The arrangement, in whole and half minutes. A zone is a minute here, so every one of these
    // lands where the timeline says it does and every line of the output can be checked by hand.
    private val crewAsksAt = 2.5
    private val spillAsksAt = 2.6
    private val retryAfter = 3.0
    private val crewHoldsFor = 4.0
    private val cleanupTakes = 2.0

    private val network: GuidedPathNetwork = GuidedPathNetwork.builder("Aisle")
        .link("L1", "A", "B", length = 72.0, zoneLength = 12.0)
        .build()

    val system = GuidedPathTransportSystem(this, network, name = "Sys")

    private val cart = GuidedTransporter(
        system, TransporterPlacement.At("A"), ConstantRV(12.0), 1, name = "Cart"
    )

    /** Where a closure waits while the space it asked for finishes draining. */
    private val closureQ = HoldQueue(this, "ClosureQ")

    private val crew = Crew("Crew")

    /** What the maintenance crew wants, and what the spill wants first and instead. */
    private fun zones(vararg names: String) = names.map { network.zone(it)!! }

    private val crewWants get() = zones("L1.Zone3", "L1.Zone4")
    private val spillWants get() = zones("L1.Zone4", "L1.Zone5")
    private val spillElsewhere get() = zones("L1.Zone5", "L1.Zone6")

    /** The timeline, one line per thing worth seeing, in the order it happened. */
    val timeline = mutableListOf<String>()

    private fun note(who: String, what: String) {
        timeline.add("  %6.2f  %-6s %s".format(time, who, what))
    }

    private fun namesOf(zs: List<Zone>) =
        zs.joinToString(", ", "[", "]") { it.name.removePrefix("L1.") }

    /** What a request is about to be told, as something the run can check rather than only print. */
    private enum class Prospect { REFUSED, ACCEPTED }

    /**
     *  What is about to happen to a request, and why, in the terms the modeller decides about.
     *
     *  Read **before** asking, because the answer is what the example is about. Note that the
     *  held case is *observed* here and never acted on -- deciding on a hold is precisely the
     *  mistake this example exists to show. Only the promised case is a decision.
     *
     *  The verdict is returned alongside the wording so that the process below can hold the
     *  library to it. Everything here is arranged, so a prediction that failed to come true would
     *  mean the promised/held distinction had moved underneath the example -- which is exactly the
     *  regression a file about that distinction should not be able to survive quietly.
     */
    private fun prospects(wanted: List<Zone>): Pair<Prospect, String> {
        val promised = system.firstPromisedZone(wanted)
        if (promised != null) {
            return Prospect.REFUSED to (" -- refused: ${promised.name.removePrefix("L1.")} is " +
                    "promised to ${promised.closingFor?.name}, still waiting for it to drain")
        }
        val held = wanted.firstOrNull { it.holder != null && it.holder !is GuidedTransporter }
        if (held != null) {
            return Prospect.ACCEPTED to (" -- accepted: ${held.name.removePrefix("L1.")} is *held* " +
                    "by ${held.holder?.name}, which is not a collision; waiting for the hold to end")
        }
        return Prospect.ACCEPTED to " -- accepted: the space is free"
    }

    // ---- the event route: a scheduled maintenance closure -----------------------------------

    private val crewAction = object : ZoneHoldActionIfc {
        override fun holdBegan(allocation: ZoneAllocation) {
            note("Crew", "takes ${namesOf(allocation.zones)}")
        }

        override fun holdEnded(allocation: ZoneAllocation) {
            note("Crew", "gives back ${namesOf(allocation.zones)}")
        }
    }

    // ---- the process route: a spill that has to decide ---------------------------------------

    private inner class Spill : Entity("Spill") {
        @Suppress("unused")
        val cleanup = process(isDefaultProcess = true) {
            var wanted = spillWants
            var relocated = false
            while (true) {
                // The one call that can come back empty-handed. Everything a request must
                // satisfy besides an overlap still raises, so a null here means exactly one
                // thing: some zone of the set is promised to a closure still waiting for it.
                val (expected, why) = prospects(wanted)
                note("Spill", "asks for ${namesOf(wanted)}$why")
                val taken = trySeizeZones(system, wanted, closureQ)
                val answered = if (taken == null) Prospect.REFUSED else Prospect.ACCEPTED
                check(answered == expected) {
                    "at $time the spill was told $answered for ${namesOf(wanted)} where reading " +
                            "the zones said $expected. The promised/held distinction this example " +
                            "is about has moved."
                }
                if (taken != null) {
                    note("Spill", "takes ${namesOf(taken.zones)}")
                    delay(cleanupTakes)
                    releaseZones(system)
                    note("Spill", "gives back ${namesOf(wanted)}")
                    return@process
                }
                when (policy) {
                    OverlapPolicy.ABSORB -> {
                        note("Spill", "absorbed into the closure already there; nothing to do")
                        return@process
                    }
                    OverlapPolicy.DEFER -> {
                        note("Spill", "deferring $retryAfter minutes and asking again")
                        delay(retryAfter)
                    }
                    OverlapPolicy.RELOCATE -> {
                        if (relocated) {
                            note("Spill", "nowhere left to move it; absorbed")
                            return@process
                        }
                        relocated = true
                        wanted = spillElsewhere
                        note("Spill", "moving the work to ${namesOf(wanted)}")
                    }
                }
            }
        }
    }

    init {
        cart.attachArrivalListener { note("Cart", "reaches B") }
    }

    override fun initialize() {
        timeline.clear()
        schedule({ _: KSLEvent<Nothing> -> cart.sendTo("B") }, 0.0)
        schedule({ _: KSLEvent<Nothing> ->
            note("Crew", "asks for ${namesOf(crewWants)} -- the cart is still crossing Zone3")
            // The arrangement depends on this one succeeding: nothing is promised at 2.5, and a
            // refusal here would leave the spill colliding with a closure that never happened.
            checkNotNull(system.tryHoldZonesFor(crew, crewWants, crewHoldsFor, crewAction)) {
                "the crew was refused ${namesOf(crewWants)} at $time, so the collision the rest of " +
                        "this example is about never arises"
            }
        }, crewAsksAt)
        schedule({ _: KSLEvent<Nothing> -> activate(Spill().cleanup) }, spillAsksAt)
    }
}

fun main() {
    println()
    println("One condition, three answers: the space asked for is already promised")
    println("A zone is a minute. The cart reaches Zone k at time k and holds it until k+1.")

    for (policy in ZoneClosurePolicyExample.OverlapPolicy.entries) {
        val m = Model("ClosurePolicy_$policy")
        val aisle = ZoneClosurePolicyExample(m, policy, name = "Aisle")
        // On, where the benchmarks force it off. It walks every zone on every change, which is
        // why a throughput measurement cannot afford it -- and why a twenty-minute run over six
        // zones can afford it easily. This example makes claims about which zone is promised to
        // whom at which instant, so the harness that checks the space's own bookkeeping against
        // itself is worth more here than the microseconds it costs.
        aisle.system.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0
        m.simulate()
        println()
        println("$policy")
        for (line in aisle.timeline) {
            println(line)
        }
    }
}
