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

package ksl.app.swing.animation.replay

import ksl.animation.AnchorKind
import ksl.animation.AnimationLayout
import ksl.animation.PathDefinition

/**
 * Resolves layout anchor names to world positions and answers "is there an authored path between A and B?".
 * It holds separate network-station and location position maps — the typed split from the Station/Location/Path
 * disentanglement. [resolve] returns strictly the requested kind's position: a move endpoint is a *location* and
 * a network transit anchor is a *station* (legacy locations-saved-as-stations are upgraded at load — Phase 7 — so
 * no cross-map fallback is needed). [pathBetween] backs path-following motion (consumed by the position interpolator).
 */
class AnchorResolver(
    private val stationPos: Map<String, WorldPoint>,
    private val locationPos: Map<String, WorldPoint>,
    private val paths: List<PathDefinition> = emptyList()
) {
    /** The world position of [name] as the requested [preferKind] (locations vs network stations are distinct); null if unknown. */
    fun resolve(name: String, preferKind: AnchorKind = AnchorKind.LOCATION): WorldPoint? =
        when (preferKind) {
            AnchorKind.LOCATION -> locationPos[name]
            AnchorKind.NETWORK_STATION -> stationPos[name]
        }

    /** The world position of the network station named [name], or null — no location fallback. */
    fun station(name: String): WorldPoint? = stationPos[name]

    /**
     * The intermediate waypoints of an authored functional path from [fromName] to [toName] — or the reversed
     * waypoints of a `bidirectional` path authored in the other direction — else null. Endpoints are NOT included;
     * the caller already has the resolved from/to positions.
     */
    fun pathBetween(fromName: String?, toName: String?): List<WorldPoint>? {
        if (fromName == null || toName == null) return null
        for (p in paths) {
            val f = p.from ?: continue
            val t = p.to ?: continue
            if (f.name == fromName && t.name == toName) return p.points.map { WorldPoint(it.x, it.y, it.z) }
            if (p.bidirectional && f.name == toName && t.name == fromName)
                return p.points.map { WorldPoint(it.x, it.y, it.z) }.asReversed()
        }
        return null
    }

    companion object {
        /** Builds a resolver from a layout: station positions, placed-location positions, and its path definitions. */
        fun from(layout: AnimationLayout?): AnchorResolver {
            val stationPos = layout?.stations
                ?.associate { it.stationName to WorldPoint(it.position.x, it.position.y, it.position.z) } ?: emptyMap()
            val locationPos = layout?.locations
                ?.mapNotNull { l -> l.position?.let { l.locationName to WorldPoint(it.x, it.y, it.z) } }?.toMap() ?: emptyMap()
            return AnchorResolver(stationPos, locationPos, layout?.paths ?: emptyList())
        }
    }
}
