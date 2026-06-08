/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.modeling.agent

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 *  Deterministic "jitter" direction helpers used as numerical safety
 *  valves where a physically meaningful direction is unavailable:
 *
 *   - two agents at exactly the same position in a repulsion force
 *     (the unit separation vector is otherwise 0/0),
 *   - a body driven to exactly zero velocity under a positive
 *     `minSpeed` (no heading to rescale).
 *
 *  They are *pure functions of agent identity* (the agent's name), so
 *  they consume nothing from the model's random-number streams and keep
 *  runs bit-reproducible. The pair variants are **antisymmetric** —
 *  `pairJitterND(a, b) == -pairJitterND(b, a)` — so two coincident
 *  agents are pushed in opposite directions and reliably separate.
 */

/** Map a 32-bit hash to a fraction in `[0, 1)`. */
private fun frac(h: Int): Double = (h.toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0

/** A deterministic unit vector derived from a single identity [key]. */
internal fun jitterDirection2D(key: String): Point2D {
    val angle = 2.0 * PI * frac(key.hashCode())
    return Point2D(cos(angle), sin(angle))
}

/**
 *  A deterministic unit vector for resolving a coincidence between two
 *  agents, antisymmetric in its arguments so the two agents separate.
 *  Returns the direction in which [selfKey] should move away from
 *  [otherKey].
 */
internal fun pairJitter2D(selfKey: String, otherKey: String): Point2D {
    val selfIsLo = selfKey <= otherKey
    val lo = if (selfIsLo) selfKey else otherKey
    val hi = if (selfIsLo) otherKey else selfKey
    val base = jitterDirection2D("$lo|$hi")
    return if (selfIsLo) base else Point2D(-base.x, -base.y)
}

/**
 *  A deterministic unit vector (uniform over the sphere) derived from a
 *  single identity [key].
 */
internal fun jitterDirection3D(key: String): Point3D {
    val z = 2.0 * frac(key.hashCode()) - 1.0
    // Salt a second, independent hash for the azimuth so z and phi are
    // not derived from the same value.
    val phi = 2.0 * PI * frac((key + "/azimuth").hashCode())
    val r = sqrt((1.0 - z * z).coerceAtLeast(0.0))
    return Point3D(r * cos(phi), r * sin(phi), z)
}

/**
 *  A deterministic unit vector for resolving a coincidence between two
 *  agents in 3D, antisymmetric in its arguments. Returns the direction
 *  in which [selfKey] should move away from [otherKey].
 */
internal fun pairJitter3D(selfKey: String, otherKey: String): Point3D {
    val selfIsLo = selfKey <= otherKey
    val lo = if (selfIsLo) selfKey else otherKey
    val hi = if (selfIsLo) otherKey else selfKey
    val base = jitterDirection3D("$lo|$hi")
    return if (selfIsLo) base else Point3D(-base.x, -base.y, -base.z)
}
