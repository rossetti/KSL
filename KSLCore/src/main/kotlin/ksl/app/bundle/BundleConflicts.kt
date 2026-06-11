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

package ksl.app.bundle

/**
 *  A `bundleId` registered by more than one loaded source (a JAR, or the
 *  classpath).  Reported, not resolved: nothing is dropped — the duplicate
 *  copies all stay in the loaded set.  The UI surfaces this so the user knows
 *  the overlap exists and can pick the specific source when selecting a model.
 *
 *  @param bundleId     the colliding bundle id
 *  @param displayName  the (shared) display name, for a friendlier message
 *  @param sources      one entry per loaded copy — a JAR file name, or
 *                      `"classpath"` for a classpath-discovered bundle
 */
data class BundleSourceConflict(
    val bundleId: String,
    val displayName: String,
    val sources: List<String>,
)

/**
 *  The source a loaded bundle came from: its JAR file name, or `"classpath"`
 *  for a classpath-discovered bundle.  This is the human-facing disambiguator
 *  shown in pickers and conflict notices when several sources register the same
 *  `bundleId`.
 */
fun bundleSourceLabel(bundle: LoadedBundle): String =
    bundle.sourceJar?.fileName?.toString() ?: "classpath"

/**
 *  Loaded bundles whose [`bundleId`][LoadedBundle.bundle] is registered by more
 *  than one source.  Used to surface overlap (e.g. the same bundle dropped into
 *  `~/.ksl/bundles/` from several JARs) rather than silently shadowing one copy.
 *  Returns an empty list when every loaded bundle id is unique.
 */
fun bundleSourceConflicts(bundles: List<LoadedBundle>): List<BundleSourceConflict> =
    bundles.groupBy { it.bundle.bundleId }
        .filter { (_, group) -> group.size > 1 }
        .map { (id, group) ->
            BundleSourceConflict(
                bundleId = id,
                displayName = group.first().bundle.displayName,
                sources = group.map(::bundleSourceLabel),
            )
        }
