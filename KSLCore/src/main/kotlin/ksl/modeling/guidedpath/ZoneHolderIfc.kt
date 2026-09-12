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

/**
 * Something that can hold a zone exclusively.
 *
 * A spill. An aisle closed for a safety walk. A picker at a rack face. A lift car out of service. A
 * dropped pallet, a cleaning window, staging overflow at shift change. Each of these denies a zone
 * to traffic in exactly the way a parked vehicle does, and none of them is a vehicle -- which was
 * the whole finding behind this construct: `Zone.holder` being typed to a [GuidedTransporter] was
 * the only thing standing between the subsystem and that entire family of problems.
 *
 * **Without it, obstruction time is fitted into the wrong parameter.** A model with no spills, no
 * picking interference and no closures must still match observed throughput, so that time goes into
 * inflated task times or a depressed velocity. The model then fits the aggregate and is wrong about
 * the mechanism -- and it will give bad advice about any change that alters the obstruction rate,
 * which is precisely the change a study is commissioned to evaluate.
 *
 * ## Why this is an interface and not a class
 *
 * Because a holder cannot be known in advance. A `ModelElement` must be constructed before
 * `simulate()`, so a holder that was one could only ever be a fixed cast reused serially -- fine
 * for two cleanup crews, impossible for an arrival stream of spills, and impossible for an
 * `Entity`, which is made at run time and cannot be a model element at all. Everything a holder was
 * ever going to need from a model element -- the statistics, the per-replication reset, the
 * scheduling -- is the space's, so a holder is left with the two things below and can be anything:
 *
 * ```
 * class CleanupCrew(id: Int) : ZoneHolderIfc {
 *     override val name = "Crew$id"
 *     override val awaitedZone: Zone? get() = null
 * }
 *
 * space.holdZonesFor(CleanupCrew(nextId++), network.link("Aisle3")!!.zones, cleanupTime.value, this)
 * ```
 *
 * The extent is chosen per occurrence, at run time, and so is the cast. Nothing about either has to
 * be stated before the run.
 *
 * ## What a non-vehicle holder is, mechanically
 *
 * It **never waits for space it cannot have**, and that is a defining property rather than an
 * incidental one. It asks for a zone; the zone drains; it takes it. While it waits it holds
 * nothing, so it has no outgoing edge in the wait-for graph and cannot lie on a circular wait --
 * which is why [awaitedZone] is null for such a holder and why deadlock detection treats it as a
 * terminal node. Whatever is stuck behind one is *obstructed*, not deadlocked, and those want
 * different remedies.
 *
 * It is also why such a holder is not a [GuidedTransporter] with the movement left out. A
 * transporter has a route and gives up zones one at a time as it moves; a holder has an allocation
 * and gives up its zones as a whole. That asymmetry is real and is stated here rather than smoothed
 * over.
 *
 * ## The two members
 *
 * The interface asks for only the two things the subsystem genuinely needs from a holder, and it is
 * worth saying why each is the minimum rather than a convenience.
 *
 * [name] is needed because a holder is named in every message the subsystem can raise -- invariant
 * violations, deadlock reports, and the refusal to enter a held zone all say who is in the way.
 * Those messages are how a modeller finds a fault, and one that named an object rather than a
 * holder would be no help.
 *
 * [awaitedZone] is needed because it is the outgoing edge of the wait-for graph, and so is the only
 * thing deadlock detection asks of a holder. A holder that waits for nothing has no outgoing edge,
 * cannot close a cycle, and is therefore a terminal node of the walk: whatever is stuck behind it
 * is obstructed rather than deadlocked. That distinction is the whole reason the graph is walked,
 * and it falls out of this one property. It is also why *most* holders will answer null: a crossing
 * or a closed aisle occupies space without ever queuing for more, and the vehicle -- which does
 * queue -- is the special case, not the general one.
 *
 * Nothing here says how a holder takes a zone or gives it up. Those are [GuidedPathSpace]'s
 * business -- [GuidedPathSpace.requestZones], [GuidedPathSpace.holdZonesFor] and
 * [GuidedPathSpace.releaseZones] -- and keeping them off this interface is what stops anything
 * outside the package from minting a claim on the space.
 */
interface ZoneHolderIfc {

    /** Unique enough to identify the holder in a message, and stable for the run. */
    val name: String

    /**
     * The zone this holder is waiting for, or null when it waits for nothing.
     *
     * Null is the ordinary answer. Only a holder that queues for space it does not yet have -- a
     * vehicle, in practice -- has anything else to report.
     */
    val awaitedZone: Zone?
}
