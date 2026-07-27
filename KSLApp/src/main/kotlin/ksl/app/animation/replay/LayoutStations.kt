/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.app.animation.replay

import ksl.animation.AnimationLayout
import ksl.animation.ElementKind
import ksl.animation.ElementLabel
import ksl.animation.LayoutPoint
import ksl.app.animation.io.AnimationSource

/**
 * Assembles the machines onto the places they belong to, using what the trace observed.
 *
 * A generated layout knows two things separately: where the venue's locations are (from the model's own
 * geometry) and which resources exist (from its structure). Nothing in the model connects them, so the
 * default is machines stacked in a column on one side and locations scattered on the other — two diagrams
 * sharing a canvas. The trace connects them: an entity arrives at a place and then seizes something there.
 *
 * Once a resource is on its location, three things follow, and each of them is a defect this fixes rather
 * than a decoration:
 *
 *  - **The machine is lifted clear of the location.** A movable resource resolves its drawn position through
 *    the location of the same name, so a machine sitting exactly on one has transporters parked on top of it,
 *    hiding whatever is being worked on. Lifted, the location reads as the spot on the floor where a worker
 *    stands and the machine sits above it.
 *  - **The queue head clears the block.** A resource draws one cell per unit of capacity, centred, so its
 *    half-width is `capacity * size / 2`. Reserving `size / 2` tucks a multi-server queue under its own
 *    machine.
 *  - **Redundant labels are hidden.** A station gathers its location's name, its resource's name, its queue's
 *    name and count, and any mover standing there — four labels at one point. Only the resource's name and
 *    the queue's count carry information the others do not.
 */
fun AnimationLayout.withResourcesAtTheirLocations(trace: AnimationSource?): AnimationLayout {
    if (trace == null || locations.isEmpty() || resources.isEmpty()) return this

    val placeAcc = ResourceLocations()
    val capacityAcc = ResourceCapacities()
    val peakAcc = QueuePeaks()
    val flowAcc = FlowOrder()
    for (event in trace.events) {
        placeAcc.accept(event); capacityAcc.accept(event); peakAcc.accept(event); flowAcc.accept(event)
    }
    val locationAt = locations.mapNotNull { l -> l.position?.let { l.locationName to it } }.toMap()

    // Observation first, then the name. A conveyor model never produces the observation: a part riding a belt
    // emits ConveyorItemMoved rather than MoveStarted, so no arrival is ever seen before a seize. But a
    // conveyor's anchors are named for the stations they serve -- that is how a process asks to get on at one
    // -- so a resource and a location sharing a name are the same place, and matching them is what puts the
    // machines on a belt instead of in a column beside it.
    val placedAt = resources.mapNotNull { res ->
        val at = placeAcc.result()[res.resourceName]?.takeIf { it in locationAt }
            ?: res.resourceName.takeIf { it in locationAt }
        at?.let { res.resourceName to it }
    }.toMap()
    if (placedAt.isEmpty()) return this
    val moved = resources.filter { locationAt.containsKey(placedAt[it.resourceName]) }
    if (moved.isEmpty()) return this

    // Size the machines to the arrangement they are joining rather than to the type default, which was chosen
    // against no particular canvas. A block about a twentieth of the placement's width reads as a machine; the
    // default against a venue laid out in real distances reads as a speck.
    val spanX = locationAt.values.let { it.maxOf { p -> p.x } - it.minOf { p -> p.x } }
    val spanY = locationAt.values.let { it.maxOf { p -> p.y } - it.minOf { p -> p.y } }
    val size = (maxOf(spanX, spanY) / 20.0).coerceIn(MIN_MACHINE, MAX_MACHINE)
    val capacity = capacityAcc.result()
    val peak = peakAcc.result()
    val queueOf = flowAcc.result().queueOfResource

    fun halfWidth(name: String) = (capacity[name] ?: 1).coerceAtLeast(1) * size / 2

    val movedNames = moved.map { it.resourceName }.toSet()
    val newResources = resources.map { res ->
        val at = locationAt[placedAt[res.resourceName]] ?: return@map res
        res.copy(position = LayoutPoint(at.x, at.y - (size / 2 + size * 0.5), at.z), size = size)
    }
    val resourcePosition = newResources.associate { it.resourceName to it.position }

    val newQueues = queues.map { q ->
        val owner = movedNames.firstOrNull { queueOf[it] == q.queueName || q.queueName == "$it:Q" }
            ?: return@map q
        val at = resourcePosition.getValue(owner)
        q.copy(
            position = LayoutPoint(at.x - halfWidth(owner) - size * 0.45, at.y, at.z),
            growthDegrees = 180.0,          // members -> head -> server, the direction the process reads
            spacing = size * 0.78,
            maxShown = ((peak[q.queueName] ?: 0) + 2).coerceIn(3, 30)
        )
    }

    // Only add an override where one is not already authored, so this never overwrites a human's decision.
    val authored = labels.map { it.kind to it.name }.toSet()
    val suppressed = ArrayList<ElementLabel>()
    fun suppress(label: ElementLabel) {
        if (label.kind to label.name !in authored) suppressed.add(label)
    }
    for (res in moved) {
        placedAt[res.resourceName]?.let { suppress(ElementLabel(ElementKind.LOCATION, it, visible = false)) }
        queueOf[res.resourceName]?.let {
            suppress(ElementLabel(ElementKind.QUEUE, it, visible = false, valueDx = -4.0, valueDy = size * 0.62))
        }
    }
    // A mover at rest stands on a location, so its label lands on whatever else is there.
    for (mover in movableResources) suppress(ElementLabel(ElementKind.MOVABLE_RESOURCE, mover.name, visible = false))

    return copy(resources = newResources, queues = newQueues, labels = labels + suppressed)
}

/**
 * Drops the request queue belonging to a **pool of movable resources**.
 *
 * A transport pool's own queue holds movers waiting to be assigned, not anything the system being modelled
 * does, and its extent line is frequently the longest thing on the canvas. The members of a pool named `P`
 * are emitted as `P:R1`, `P:R2`, ... and the pool's queue as `P:Q`, so the pool is recoverable from the
 * movers themselves rather than from a guess about the name.
 */
fun AnimationLayout.withoutMoverPoolQueues(): AnimationLayout {
    val pools = movableResources.map { it.name }.filter { it.contains(':') }.map { it.substringBefore(':') }.toSet()
    if (pools.isEmpty()) return this
    val drop = queues.filter { it.queueName.substringBefore(':') in pools && it.queueName.endsWith(":Q") }
    return if (drop.isEmpty()) this else copy(queues = queues - drop.toSet())
}

/** A machine smaller than this is a dot whatever the arrangement; larger than this is a wall. */
private const val MIN_MACHINE = 14.0
private const val MAX_MACHINE = 60.0
