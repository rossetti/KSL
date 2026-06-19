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

package ksl.service.capability.run

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * A minimal, pure view of one loaded bundle, used to resolve duplicates and
 * `bundleId` collisions without touching the live classloaders. [index] points
 * back to the bundle's position in the registry's raw list.
 */
internal data class BundleKey(
    val index: Int,
    val bundleId: String,
    val contentHash: String?,
    val builtAt: Instant?,
    val source: String?,
)

/** One `bundleId`'s resolution: the active (winning) bundle and the shadowed rest. */
internal data class ResolvedBundleId(
    val bundleId: String,
    val activeIndex: Int,
    val shadowedIndices: List<Int>,
)

/**
 * Pure, deterministic resolution of a set of loaded bundles to **one active
 * bundle per `bundleId`** (the bundle-dedup design):
 *
 *  - bundles sharing a `bundleId` are resolved **newest-wins** by [BundleKey.builtAt]
 *    (a `null` `builtAt` — e.g. a classpath bundle — sorts oldest, so a dropped,
 *    time-stamped jar overrides a baked-in default);
 *  - **true duplicates** (identical [BundleKey.contentHash]) collapse to one;
 *  - ties (equal or absent `builtAt`) break deterministically by `source`, then
 *    `contentHash`, so the outcome never depends on load order.
 *
 * The losers are reported as shadowed; the registry keeps them loaded for instant
 * promotion when the winner is removed. Result order is first-seen `bundleId`.
 */
internal object BundleResolver {

    fun resolve(keys: List<BundleKey>): List<ResolvedBundleId> {
        val groups = LinkedHashMap<String, MutableList<BundleKey>>()
        for (k in keys) groups.getOrPut(k.bundleId) { mutableListOf() }.add(k)
        return groups.map { (id, group) ->
            val sorted = group.sortedWith(
                compareByDescending<BundleKey> { it.builtAt ?: Instant.MIN }
                    .thenBy { it.source ?: "" }
                    .thenBy { it.contentHash ?: "" },
            )
            ResolvedBundleId(
                bundleId = id,
                activeIndex = sorted.first().index,
                shadowedIndices = sorted.drop(1).map { it.index },
            )
        }
    }
}

/**
 * A disclosed `bundleId` conflict: the active jar and the source jars it
 * superseded (newest-wins). Returned by [BundleRegistry.conflicts] for operator
 * tooling and tests.
 */
@Serializable
data class BundleConflict(
    val bundleId: String,
    val activeSource: String?,
    val shadowedSources: List<String?>,
)
