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
 * nothing, which is why [awaitedZone] is null for such a holder: it never queues on a zone, so
 * there is nothing for it to name. A vehicle stopped behind a holder that is merely *holding*
 * space is *obstructed*, not deadlocked, and those two want different remedies.
 *
 * Answering null does not make such a holder incapable of lying on a circular wait, and an earlier
 * version of this note wrongly said that it did. What a *pending* closure waits for is every
 * vehicle occupying its reserved zones, and that wait runs through the reservation rather than
 * through [awaitedZone] -- so two closures over abutting regions can wait on each other. That case
 * is prevented by the ordering rule on `ZoneClosureIfc` and reported by the detector, which
 * follows a reserved-but-free zone to the vehicles the reservation is waiting on.
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
 * [awaitedZone] is needed because it is the queuing edge of the wait-for graph, and so is the only
 * thing deadlock detection asks of a holder. A holder that waits for nothing contributes no such
 * edge, and a vehicle stuck behind one that is merely holding space is obstructed rather than
 * deadlocked. It is also why *most* holders will answer null: a crossing or a closed aisle occupies
 * space without ever queuing for more, and the vehicle -- which does queue -- is the special case,
 * not the general one. Answering null is a statement about queuing and nothing more; a holder that
 * answered non-null would have the same wait counted twice, since the detector already reaches what
 * a pending closure waits for through the reservation.
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

/**
 * A holder whose lifetime something else manages, and which therefore has to be told where it holds
 * space -- or has asked for some -- so that it can give it back when that lifetime ends.
 *
 * An `Entity` is the case this exists for. It is made by an arrival process and destroyed when its
 * process ends, and neither event is the guide path's to see -- so the guide path has to leave a
 * back-pointer the entity can follow at those moments. What is held stays the space's, as it is for
 * every other holder; this records only *which spaces* to ask, which is the least that makes
 * cleanup possible.
 *
 * It is told at the **request**, not at the grant. An entity killed while its aisle is still
 * draining holds nothing yet, and a reservation left behind would close that aisle to traffic for
 * the rest of the replication with nothing holding it and nothing coming to release it.
 *
 * A holder that outlives the run -- a crew, a maintenance team -- implements nothing here, because
 * there is no moment at which somebody else decides it is finished.
 */
internal interface ZoneHolderRecordIfc {

    /** This holder has just asked this guide path for space, or been granted it. */
    fun zoneSpaceEngaged(space: GuidedPathSpace)

    /** This holder now neither holds space on this guide path nor is waiting for any. */
    fun zoneSpaceFinished(space: GuidedPathSpace)
}
