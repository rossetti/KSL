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

import ksl.animation.AnimationEvent
import ksl.animation.GuidedPathIntersectionDef
import ksl.animation.GuidedPathLinkDef

/**
 * Turns what happens on a guide path into animation events, and does nothing at all when nobody is
 * watching.
 *
 * Every method begins by asking the model whether an animation sink is active and returns
 * immediately when it is not, which is the same guard `Conveyor.emitConveyorDefined` uses. The
 * check is a field read on a path that already exists, so a model run without animation pays for
 * the emitter only that.
 *
 * The three events between them are enough to draw the whole thing. The guide path is emitted once
 * per replication and carries its own coordinates, so unlike a conveyor it needs no authored layout
 * -- a link is a line between the two intersections it names and a transporter on zone *k* of *n*
 * sits that fraction along it. Movement is sampled on entering a zone, never continuously.
 * State changes carry the rest.
 *
 * Sampling on entry is what makes congestion visible for free. A blocked transporter emits nothing
 * and so stays exactly where the renderer last drew it, which is exactly where it is; there is no
 * "stopped" event to get wrong, and no risk of a transporter drifting on a canvas while its zone
 * claim is refused. The state stream then says *why* it is standing still, which matters because a
 * cart parked with nothing to do and a cart stopped by traffic look identical and mean opposite
 * things about the design.
 *
 * @param system the runtime whose guide path and fleet are being animated
 */
class GuidedPathAnimationEmitter(private val system: GuidedPathSpace) {

    /**
     * Emits the static guide path. Called from the system's `initialize()`, once per replication,
     * so that a viewer joining at any replication boundary has the structure it needs.
     */
    internal fun emitGuidedPathDefined() {
        val sink = system.model.animationSink
        if (!sink.isActive) return
        val network = system.network
        sink.emit(
            AnimationEvent.GuidedPathDefined(
                simTime = system.time,
                networkName = network.name,
                intersections = network.intersections.map {
                    GuidedPathIntersectionDef(it.name, it.x, it.y, it.z)
                },
                links = network.links.map {
                    GuidedPathLinkDef(
                        name = it.name,
                        from = it.beginIntersection.name,
                        to = it.endIntersection.name,
                        numZones = it.numZones,
                        bidirectional = it.type == LinkType.BIDIRECTIONAL,
                        spur = it.type == LinkType.SPUR
                    )
                }
            )
        )
    }

    /**
     * Emits a transporter's arrival in a zone.
     *
     * @param transporter the transporter that has taken the zone
     * @param zone the zone it now covers at its leading edge
     */
    internal fun emitTransporterMoved(transporter: GuidedTransporter, zone: Zone) {
        val sink = system.model.animationSink
        if (!sink.isActive) return
        sink.emit(
            AnimationEvent.GuidedTransporterMoved(
                simTime = system.time,
                transporterName = transporter.name,
                networkName = system.network.name,
                zoneName = zone.name,
                linkName = (zone as? LinkZone)?.link?.name,
                zoneIndex = (zone as? LinkZone)?.positionOnLink ?: 0
            )
        )
    }

    /**
     * Emits a change in what a closure holds.
     *
     * The fourth event, and the one that is not about a vehicle. A cart stopped by a closure is
     * stopped by space that is empty, so without this a viewer sees stillness with nothing in front
     * of it -- the same blindness the run itself had before it learned to report space it had
     * promised and never granted.
     *
     * @param request the closure whose state has changed
     * @param state `RESERVED`, `HELD` or `RELEASED`, as documented on the event
     */
    internal fun emitClosureChanged(request: ZoneRequest, state: String) {
        val sink = system.model.animationSink
        if (!sink.isActive) return
        sink.emit(
            AnimationEvent.GuidedPathClosureChanged(
                simTime = system.time,
                holderName = request.holder.name,
                networkName = system.network.name,
                zoneNames = request.zones.map { it.name },
                state = state
            )
        )
    }

    /**
     * Emits a change in what a transporter is doing.
     *
     * @param transporter the transporter whose state has changed
     * @param state the state it has entered
     */
    internal fun emitTransporterState(transporter: GuidedTransporter, state: TransporterState) {
        val sink = system.model.animationSink
        if (!sink.isActive) return
        sink.emit(
            AnimationEvent.GuidedTransporterStateChanged(
                simTime = system.time,
                transporterName = transporter.name,
                networkName = system.network.name,
                state = state.name
            )
        )
    }
}
