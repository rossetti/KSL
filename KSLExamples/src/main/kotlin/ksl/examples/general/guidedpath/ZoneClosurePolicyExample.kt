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

/**
 *  What to do when the guide-path space you asked for is already promised to somebody else.
 *
 *  A zone carries **one promise** at a time. Two closures may be in force at once and may even want
 *  the same zone, but they cannot both be *queued* for it -- so a model whose closures land where
 *  they land will sooner or later ask for space that is already promised.
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
 *  - **5.0** the cart clears `Zone4`, the crew's set has drained, and the crew takes it.
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
 *    without overlapping in space.
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
 *  ## Both routes, one decision
 *
 *  The crew is event-scheduled and uses `tryHoldZonesFor`. The spill is an entity and uses the
 *  suspending `trySeizeZones`, which is why its policy reads as straight-line code -- a retry loop
 *  in a process is a `delay` inside a `while`, where the same thing on the event route has to be
 *  spread across an action that reschedules itself.
 */
object ZoneClosurePolicyExample {

    /** What a model does when the space it asked for is already promised to somebody else. */
    enum class OverlapPolicy {
        /** The new closure is part of the one already there. */
        ABSORB,

        /** Wait a little and ask again. */
        DEFER,

        /** Do the same work somewhere else. */
        RELOCATE
    }

    /** A holder is a plain object: a name, and what it waits for, which for a closure is nothing. */
    class Crew(override val name: String) : ZoneHolderIfc {
        override val awaitedZone: Zone? get() = null
    }

    const val CREW_ASKS_AT: Double = 2.5
    const val SPILL_ASKS_AT: Double = 2.6
    const val RETRY_AFTER: Double = 3.0
    const val CREW_HOLDS_FOR: Double = 4.0
    const val CLEANUP_TAKES: Double = 2.0

    /**
     *  One aisle, one cart, one scheduled closure and one spill that collides with it.
     *
     *  @param parent the containing model element
     *  @param policy what the spill does when the space it wants is already promised
     */
    class Aisle(parent: ModelElement, val policy: OverlapPolicy) : ProcessModel(parent, "Aisle") {

        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Aisle")
            .link("L1", "A", "B", length = 72.0, zoneLength = 12.0)
            .build()

        val system = GuidedPathTransportSystem(this, network, name = "Sys")

        val cart = GuidedTransporter(
            system, TransporterPlacement.At("A"), ConstantRV(12.0), 1, name = "Cart"
        )

        /** Where a closure waits while the space it asked for finishes draining. */
        val closureQ = HoldQueue(this, "ClosureQ")

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

        /**
         *  What is about to happen to a request, and why, in the terms the modeller decides about.
         *
         *  Read **before** asking, because the answer is what the example is about. Note that the
         *  held case is *observed* here and never acted on -- deciding on a hold is precisely the
         *  mistake this example exists to show. Only the promised case is a decision.
         */
        private fun prospects(wanted: List<Zone>): String {
            val promised = system.firstPromisedZone(wanted)
            if (promised != null) {
                return " -- refused: ${promised.name.removePrefix("L1.")} is promised to " +
                        "${promised.closingFor?.name}, still waiting for it to drain"
            }
            val held = wanted.firstOrNull { it.holder != null && it.holder !is GuidedTransporter }
            if (held != null) {
                return " -- accepted: ${held.name.removePrefix("L1.")} is *held* by " +
                        "${held.holder?.name}, which is not a collision; waiting for the hold to end"
            }
            return " -- accepted: the space is free"
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

        inner class Spill : Entity("Spill") {
            @Suppress("unused")
            val cleanup = process(isDefaultProcess = true) {
                var wanted = spillWants
                var relocated = false
                while (true) {
                    // The one call that can come back empty-handed. Everything a request must
                    // satisfy besides an overlap still raises, so a null here means exactly one
                    // thing: some zone of the set is promised to a closure still waiting for it.
                    note("Spill", "asks for ${namesOf(wanted)}${prospects(wanted)}")
                    val taken = trySeizeZones(system, wanted, closureQ)
                    if (taken != null) {
                        note("Spill", "takes ${namesOf(taken.zones)}")
                        delay(CLEANUP_TAKES)
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
                            note("Spill", "deferring $RETRY_AFTER minutes and asking again")
                            delay(RETRY_AFTER)
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
                system.tryHoldZonesFor(crew, crewWants, CREW_HOLDS_FOR, crewAction)
            }, CREW_ASKS_AT)
            schedule({ _: KSLEvent<Nothing> -> activate(Spill().cleanup) }, SPILL_ASKS_AT)
        }
    }

    const val HORIZON: Double = 20.0

    /** Runs the aisle once under one policy and hands back what happened, in order. */
    fun timelineFor(policy: OverlapPolicy): List<String> {
        val m = Model("ClosurePolicy_$policy")
        val aisle = Aisle(m, policy)
        aisle.system.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = HORIZON
        m.simulate()
        return aisle.timeline.toList()
    }
}

fun main() {
    println()
    println("One condition, three answers: the space asked for is already promised")
    println("A zone is a minute. The cart reaches Zone k at time k and holds it until k+1.")

    for (policy in ZoneClosurePolicyExample.OverlapPolicy.entries) {
        println()
        println("$policy")
        for (line in ZoneClosurePolicyExample.timelineFor(policy)) {
            println(line)
        }
    }

    println()
    println("  ABSORB closes the least space: the spill is dealt with as part of the closure that")
    println("  was already being set up, and nothing waits.")
    println()
    println("  DEFER is the one to read twice. The retry succeeds, and it succeeds because by then")
    println("  the crew *holds* Zone4 rather than being promised it -- a held zone is not a")
    println("  collision, and asking for one is ordinary: the request is accepted and waits for the")
    println("  hold to end. A guard written by hand as \"is anything closing or holding this zone?\"")
    println("  would have turned this closure away, silently, and it works perfectly well.")
    println()
    println("  RELOCATE gets the work done soonest, and the timeline shows what that costs. The two")
    println("  closures overlap in time without overlapping in space, so more of the aisle is shut")
    println("  at once -- and the cart, which reaches B at 6.0 under the other two policies, is held")
    println("  up behind the relocated closure and reaches it at 6.6. The maintenance crew's own")
    println("  closure starts later for the same reason.")
    println()
    println("  None of the three is more correct than the others, which is the whole point: the")
    println("  guide path cannot know whether a second spill in the same aisle is one spill, a")
    println("  spill that waits, or a spill somewhere else. So the verb answers null and the model")
    println("  decides.")
}
