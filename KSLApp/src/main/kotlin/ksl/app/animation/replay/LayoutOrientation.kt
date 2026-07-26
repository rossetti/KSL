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
import ksl.animation.LayoutPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

/**
 * Turns a placement so the process reads left to right.
 *
 * A placement derived from a distance matrix — a `DistancesModel`'s MDS positions — is faithful about how far
 * apart things are and says nothing about which way round they go: a configuration recovered from distances is
 * determined only up to rotation, reflection and translation. Whichever end lands on the left is an artifact of
 * the arithmetic, so a shop whose process runs Diagnostics → Repair is as likely to be drawn backwards as
 * forwards.
 *
 * Because every candidate here is a **rigid** transform, choosing among them costs nothing: every pairwise
 * distance survives exactly, so the placement stays as faithful as it was. That is what makes this worth doing
 * automatically rather than leaving to whoever polishes the layout — there is no trade to weigh.
 *
 * What the orientation cannot fix is an element the model itself places far from the others; no rotation brings
 * it in. This turns the picture round, it does not rearrange it.
 */

/** How far a candidate orientation may drift from the original distances before it is rejected as non-rigid. */
private const val RIGID_TOLERANCE = 1e-6

/**
 * A copy of this layout whose **locations** are rotated (and possibly reflected) so that [readingOrder] runs
 * left to right — the first name leftmost and the last rightmost — choosing, among the orientations that
 * manage it, the one that spreads the placement most horizontally.
 *
 * Flatness is the right tie-break rather than an extra flourish. For a fixed shape the total spread is fixed,
 * so spreading horizontally *is* flattening vertically, and a wide band is both easier to read in process order
 * and a better fit for the shape of a screen. An earlier attempt forced the first-to-last axis exactly
 * horizontal; that satisfies the reading order but can swing an unrelated element far off to one side.
 *
 * Everything co-located with a location moves with it, so a station's resources, queues and storages stay where
 * they were relative to it.
 *
 * Returns this layout unchanged when there is nothing to do: fewer than two placed locations, a [readingOrder]
 * naming fewer than two of them, or no orientation that achieves the order (which happens when the first and
 * last of the process are not on the outside of the placement — a ring, say). Refusing is deliberate; a
 * partially-correct rotation would be harder to explain than none.
 *
 * @param readingOrder location names in process order, as mined by [LocationFlow]. Names absent from the
 *   layout are ignored; only the first and last present names constrain the result.
 */
fun AnimationLayout.withReadableOrientation(readingOrder: List<String>): AnimationLayout {
    val placed = locations.mapNotNull { loc -> loc.position?.let { loc.locationName to it } }.toMap()
    if (placed.size < 2) return this
    val order = readingOrder.filter { it in placed }
    if (order.size < 2) return this
    val first = order.first()
    val last = order.last()
    if (first == last) return this

    val cx = placed.values.sumOf { it.x } / placed.size
    val cy = placed.values.sumOf { it.y } / placed.size
    val before = pairwiseDistances(placed)

    var best: Map<String, LayoutPoint>? = null
    var bestSpread = Double.MAX_VALUE
    for (degrees in 0 until 360) {
        val radians = degrees * PI / 180.0
        val cosine = cos(radians)
        val sine = sin(radians)
        for (mirror in listOf(false, true)) {
            val candidate = placed.mapValues { (_, p) ->
                val dx = p.x - cx
                val dy = p.y - cy
                val rx = dx * cosine - dy * sine
                val ry = dx * sine + dy * cosine
                LayoutPoint(rx, if (mirror) -ry else ry, p.z)
            }
            val xs = candidate.values.map { it.x }
            if (candidate.getValue(first).x > xs.min() + RIGID_TOLERANCE) continue   // the process must start leftmost
            if (candidate.getValue(last).x < xs.max() - RIGID_TOLERANCE) continue    // and end rightmost
            val ys = candidate.values.map { it.y }
            val spread = ys.max() - ys.min()
            if (spread < bestSpread) {
                bestSpread = spread
                best = candidate
            }
        }
    }
    val chosen = best ?: return this

    // A sign error in the rotation would still produce a plausible-looking picture with the wrong distances in
    // it, which is exactly the kind of fault nobody catches by looking. Verify rather than trust.
    val after = pairwiseDistances(chosen)
    val drift = before.keys.maxOfOrNull { kotlin.math.abs(after.getValue(it) - before.getValue(it)) } ?: 0.0
    if (drift > 1e-6 * (1.0 + before.values.max())) return this

    // Translate back so the placement occupies the same corner of the canvas it did before, and shift
    // everything that sits on a location by the same amount its location moved.
    val offsetX = placed.values.minOf { it.x } - chosen.values.minOf { it.x }
    val offsetY = placed.values.minOf { it.y } - chosen.values.minOf { it.y }
    val moved = chosen.mapValues { (_, p) -> LayoutPoint(p.x + offsetX, p.y + offsetY, p.z) }
    val shift = placed.mapValues { (name, old) ->
        val new = moved.getValue(name)
        new.x - old.x to new.y - old.y
    }

    /** The shift for whichever location an element sits on, or none when it sits on no location. */
    fun shiftAt(p: LayoutPoint): Pair<Double, Double>? =
        shift.entries.firstOrNull { (name, _) ->
            val at = placed.getValue(name)
            hypot(at.x - p.x, at.y - p.y) < CO_LOCATED_TOLERANCE
        }?.value

    fun LayoutPoint.movedWithItsLocation(): LayoutPoint =
        shiftAt(this)?.let { (dx, dy) -> LayoutPoint(x + dx, y + dy, z) } ?: this

    return copy(
        locations = locations.map { loc -> loc.position?.let { loc.copy(position = moved.getValue(loc.locationName)) } ?: loc },
        resources = resources.map { it.copy(position = it.position.movedWithItsLocation()) },
        queues = queues.map { it.copy(position = it.position.movedWithItsLocation()) },
        storages = storages.map { it.copy(position = it.position.movedWithItsLocation()) },
        movableResources = movableResources.map { mr ->
            mr.position?.let { mr.copy(position = it.movedWithItsLocation()) } ?: mr
        },
    )
}

/**
 * How close an element has to be to a location to count as sitting on it. A generated layout places
 * co-located elements exactly, so this only has to absorb rounding.
 */
private const val CO_LOCATED_TOLERANCE = 1e-6

private fun pairwiseDistances(points: Map<String, LayoutPoint>): Map<Pair<String, String>, Double> {
    val names = points.keys.sorted()
    val out = HashMap<Pair<String, String>, Double>()
    for (i in names.indices) for (j in i + 1 until names.size) {
        val a = points.getValue(names[i])
        val b = points.getValue(names[j])
        out[names[i] to names[j]] = hypot(a.x - b.x, a.y - b.y)
    }
    return out
}

/** Rounds to whole units, so a generated layout's numbers are ones a person can read and edit. */
internal fun LayoutPoint.rounded(): LayoutPoint = LayoutPoint(round(x * 10) / 10, round(y * 10) / 10, z)
