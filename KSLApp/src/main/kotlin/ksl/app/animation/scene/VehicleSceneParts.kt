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

package ksl.app.animation.scene

import ksl.animation.GuidedPathLayoutElement
import ksl.animation.GuidedTransporterLayoutElement
import ksl.app.animation.replay.GuidedPathGeometry
import ksl.app.animation.replay.ReplayModel
import ksl.app.animation.replay.WorldPoint
import ksl.app.animation.style.RgbaColor
import ksl.app.animation.style.VisualStyle
import kotlin.math.sqrt

/**
 * The drawing decisions for vehicles: guide paths with their zones and closures, and guided transporters
 * coloured by what they are doing, carrying their loads. Kept apart from [SceneBuilder] because it is a
 * self-contained concern, and shared with the browser build like the rest of the scene.
 *
 * The rules a viewer relies on:
 *  - a link is a line between its two intersections; a one-way link carries an arrow at its midpoint, a
 *    two-way link none, and a spur is drawn lighter, so the features that shape congestion are visible;
 *  - a closed zone is shaded in the path's closure colour, faint while the closure is still draining
 *    (RESERVED) and solid once it holds (HELD), so a cart stopped by space that looks empty has a reason
 *    on screen;
 *  - a transporter is coloured loaded, blocked or halted from its layout entry, and when that entry gives no
 *    colour for the stillness it is in, a ring says so instead: red for blocked, grey for halted. The two
 *    stillnesses mean opposite things about a design and must not look alike;
 *  - a load is drawn on the cart carrying it, with a count when more than one is aboard (decision D2);
 *  - an assigned fleet vehicle has a faint line to where its task sends it next: the pickup until its load
 *    is aboard, then the drop-off. An assignment has no position of its own, and without the line nothing
 *    on screen says why that vehicle, rather than a nearer one, is heading where it is (AGV decision 4).
 */
class VehicleSceneParts(
    private val model: ReplayModel,
    private val style: VisualStyle
) {
    private val vehicles = model.vehicles

    private fun pathStyle(key: String): GuidedPathLayoutElement =
        model.layout?.guidedPaths?.firstOrNull { it.spaceName == key } ?: GuidedPathLayoutElement(key)

    private fun transporterStyle(name: String): GuidedTransporterLayoutElement? =
        model.layout?.guidedTransporters?.firstOrNull { it.name == name }

    /** The guide paths: links, zone ticks, direction arrows, intersections, and (when not static) closures. */
    fun guidePathCommands(t: Double, static: Boolean): List<DrawCmd> {
        val cmds = ArrayList<DrawCmd>()
        for ((key, geom) in vehicles.guidePaths) {
            val s = pathStyle(key)
            val color = RgbaColor.parse(s.linkColor)
            val spurColor = color.fade(0.5)
            for (link in geom.definition.links) {
                val (a, b) = geom.linkEnds(link.name) ?: continue
                val c = if (link.spur) spurColor else color
                cmds.add(DrawCmd.Polyline(listOf(a.x to a.y, b.x to b.y), c, s.linkWidth))
                val n = link.numZones.coerceAtLeast(1)
                val length = dist(a, b)
                if (s.showZones && n > 1 && length > 0.0) {
                    // A short tick across the link at each interior zone boundary.
                    val half = length / n * ZONE_TICK_SHARE
                    val nx = -(b.y - a.y) / length
                    val ny = (b.x - a.x) / length
                    for (k in 1 until n) {
                        val p = lerp(a, b, k.toDouble() / n)
                        cmds.add(DrawCmd.Polyline(listOf((p.x - nx * half) to (p.y - ny * half), (p.x + nx * half) to (p.y + ny * half)), c))
                    }
                }
                if (!link.bidirectional && length > 0.0) {
                    val m = lerp(a, b, 0.5)
                    cmds.add(DrawCmd.ArrowHead(m.x, m.y, b.x - a.x, b.y - a.y, c))
                }
            }
            if (s.showIntersections) {
                for ((_, p) in geom.intersectionPoints) {
                    cmds.add(DrawCmd.Circle(p.x, p.y, Extent.px(s.linkWidth + 1.5), fill = RgbaColor.WHITE, stroke = color, strokeWidth = 1.0))
                }
            }
            if (!static) cmds.addAll(closureCommands(key, geom, s, t))
            s.label?.let { text ->
                geom.intersectionPoints.values.firstOrNull()?.let { p ->
                    cmds.add(DrawCmd.Text(p.x, p.y, text, LABEL, screenOffsetY = -10.0))
                }
            }
        }
        return cmds
    }

    private fun closureCommands(key: String, geom: GuidedPathGeometry, s: GuidedPathLayoutElement, t: Double): List<DrawCmd> {
        val closures = vehicles.closuresAt(key, t)
        if (closures.isEmpty()) return emptyList()
        val base = RgbaColor.parse(s.closureColor)
        val links = geom.definition.links.associateBy { it.name }
        val cmds = ArrayList<DrawCmd>()
        for (closure in closures) {
            val c = base.withAlpha(if (closure.state == "HELD") HELD_ALPHA else RESERVED_ALPHA)
            for (zone in closure.zoneNames) {
                val at = geom.intersectionPoint(zone)
                if (at != null) {
                    cmds.add(DrawCmd.Circle(at.x, at.y, Extent.px(s.linkWidth * 2.5 + 2.0), fill = c))
                    continue
                }
                val linkName = zone.substringBeforeLast(".Zone", "")
                val link = links[linkName] ?: continue
                val k = zone.substringAfterLast(".Zone").toIntOrNull() ?: continue
                val (a, b) = geom.linkEnds(linkName) ?: continue
                val n = link.numZones.coerceAtLeast(1)
                val p = lerp(a, b, (k - 1.0) / n)
                val q = lerp(a, b, k.toDouble() / n)
                cmds.add(DrawCmd.Polyline(listOf(p.x to p.y, q.x to q.y), c, s.linkWidth * 3.0 + 2.0))
            }
        }
        return cmds
    }

    /** The drawn size of transporter [name]: its layout entry's, else about as long as the zones it covers. */
    private fun transporterSize(name: String): Double {
        transporterStyle(name)?.let { return it.size }
        val zones = (vehicles.transporterDef(name)?.lengthInZones ?: 1).coerceAtLeast(1)
        val zoneLength = vehicles.transporterGuidePath(name)?.let { vehicles.guidePaths[it] }?.meanZoneLength()
        return zoneLength?.let { it * zones * 0.8 } ?: DEFAULT_TRANSPORTER_SIZE
    }

    /** Every guided transporter at [t] (at its first placement when [static]), with its state and its loads. */
    fun transporterCommands(t: Double, static: Boolean): List<DrawCmd> {
        val cmds = ArrayList<DrawCmd>()
        for (name in vehicles.transporterNames.sorted()) {
            val at = if (static) vehicles.transporterPositionAt(name, Double.NEGATIVE_INFINITY) else vehicles.transporterPositionAt(name, t)
            val p = at ?: continue
            if (p.x.isNaN() || p.y.isNaN()) continue
            val s = transporterStyle(name) ?: GuidedTransporterLayoutElement(name)
            val size = transporterSize(name)
            val snap = if (static) null else vehicles.transporterStateAt(name, t)
            val loads = if (static) emptyList() else vehicles.loadsAboardAt(name, t)
            val blocked = snap?.state == "BLOCKED"
            val halted = snap?.halted == true
            val loaded = loads.isNotEmpty() || snap?.state == "MOVING_LOADED"
            val colorHex = when {
                halted -> s.haltedColor
                blocked -> s.blockedColor
                loaded -> s.loadedColor
                else -> null
            } ?: s.color
            val color = RgbaColor.parse(colorHex)
            if (!static && (vehicles.transporterDef(name)?.lengthInZones ?: 1) > 1) {
                val body = vehicles.transporterBodyAt(name, t)
                if (body.size >= 2) cmds.add(DrawCmd.Polyline(body.map { it.x to it.y }, color.fade(0.6), BODY_WIDTH))
            }
            cmds.add(DrawCmd.Glyph(p.x, p.y, Extent.world(size, minPx = 4.0), s.shape, color, s.imageRef))
            if (blocked && s.blockedColor == null) {
                cmds.add(DrawCmd.Circle(p.x, p.y, Extent.world(size * 0.75, minPx = 6.0), stroke = BLOCKED_RING, strokeWidth = 2.0))
            }
            if (halted && s.haltedColor == null) {
                cmds.add(DrawCmd.Circle(p.x, p.y, Extent.world(size * 0.75, minPx = 6.0), stroke = HALTED_RING, strokeWidth = 2.0))
            }
            if (loads.isNotEmpty()) cmds.addAll(loadCommands(loads, p, size))
            s.label?.let { cmds.add(DrawCmd.Text(p.x, p.y, it, LABEL, anchor = TextAnchor.MIDDLE, screenOffsetY = -12.0)) }
        }
        return cmds
    }

    /** A faint line from every assigned vehicle to its task's next stop. */
    fun assignmentCommands(t: Double): List<DrawCmd> {
        val cmds = ArrayList<DrawCmd>()
        for (body in vehicles.assignedVehicles) {
            val a = vehicles.assignmentAt(body, t) ?: continue
            val from = vehicles.transporterPositionAt(body, t) ?: model.spatialElementPositionAt(body, t) ?: continue
            val carrying = a.loadEntityId?.let { vehicles.vehicleCarryingAt(it, t) == body } ?: vehicles.loadsAboardAt(body, t).isNotEmpty()
            val to = (if (carrying) a.destinationPoint else a.originPoint) ?: continue
            if (from.x.isNaN() || from.y.isNaN() || (from.x == to.x && from.y == to.y)) continue
            cmds.add(DrawCmd.Polyline(listOf(from.x to from.y, to.x to to.y), ASSIGNMENT, 1.0))
            cmds.add(DrawCmd.Circle(to.x, to.y, Extent.px(5.0), stroke = ASSIGNMENT, strokeWidth = 1.0))
        }
        return cmds
    }

    /** The first load's glyph on the cart, and the number aboard beside it when there is more than one (D2). */
    fun loadCommands(loads: List<Long>, p: WorldPoint, vehicleSize: Double): List<DrawCmd> {
        val cmds = ArrayList<DrawCmd>()
        val key = model.entityTypeOf(loads.first()) ?: DEFAULT_LOAD_TYPE
        // Seated on a white disc: a load's class colour is often the vehicle's own, and would vanish into it.
        cmds.add(DrawCmd.Circle(p.x, p.y, Extent.world(vehicleSize * 0.36, minPx = 2.5), fill = RgbaColor.WHITE))
        cmds.add(
            DrawCmd.Glyph(p.x, p.y, Extent.world(vehicleSize * 0.5, minPx = 3.0), style.objectShape(key), style.objectColor(key), style.objectImageRef(key))
        )
        if (loads.size > 1) {
            cmds.add(DrawCmd.Text(p.x, p.y, loads.size.toString(), LABEL, bold = true, screenOffsetX = 7.0, screenOffsetY = -6.0))
        }
        return cmds
    }

    /**
     * A ring on a free-path fleet vehicle whose fleet has taken it out of work (failed, flat, under tow, out of
     * service) or is charging it. A free-path vehicle is drawn as a mover, and a broken-down mover otherwise
     * looks exactly like a parked one.
     */
    fun fleetStateRing(name: String, t: Double, cx: Double, cy: Double, size: Double): DrawCmd? {
        val state = vehicles.fleetStateAt(name, t)?.state ?: return null
        val color = when (state) {
            "CHARGING" -> CHARGING_RING
            "FAILED", "OUT_OF_CHARGE", "UNDER_TOW", "OUT_OF_SERVICE" -> HALTED_RING
            else -> return null
        }
        return DrawCmd.Circle(cx, cy, Extent.world(size * 0.85, minPx = 6.5), stroke = color, strokeWidth = 2.0)
    }

    private fun dist(a: WorldPoint, b: WorldPoint): Double = sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))

    private fun lerp(a: WorldPoint, b: WorldPoint, f: Double) =
        WorldPoint(a.x + f * (b.x - a.x), a.y + f * (b.y - a.y), a.z + f * (b.z - a.z))

    companion object {
        private const val DEFAULT_LOAD_TYPE = "QObject"
        private const val DEFAULT_TRANSPORTER_SIZE = 14.0
        private const val ZONE_TICK_SHARE = 0.18
        private const val BODY_WIDTH = 5.0
        private const val HELD_ALPHA = 0xaa
        private const val RESERVED_ALPHA = 0x44
        private val LABEL = RgbaColor(0x33, 0x33, 0x33)
        private val BLOCKED_RING = RgbaColor(0xd6, 0x27, 0x28)
        private val HALTED_RING = RgbaColor(0x7f, 0x7f, 0x7f)
        private val CHARGING_RING = RgbaColor(0xff, 0x7f, 0x0e)
        private val ASSIGNMENT = RgbaColor(0x15, 0x6e, 0xc8, 0x88)
    }
}
