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

package ksl.app.config

import kotlinx.serialization.Serializable

/**
 *  A document-level reference to a bundle JAR.  Used by the Scenario-app
 *  workflow (and by the analogous Single / Experiment workflows) to
 *  declare which bundle JARs a `ksl.app.config.RunConfiguration`
 *  depends on, so the runtime can load each one and route per-scenario
 *  `(bundleId, modelId)` references against the resulting registry.
 *
 *  [bundleId] is the authoritative identifier: it matches the
 *  `ksl.app.bundle.KSLModelBundle.bundleId` of the bundle that the
 *  document was authored against.  At open time, the consumer
 *  (typically the Scenario app's open-document flow) tries each entry
 *  of [paths] in order, then silently searches Recent Bundles and
 *  `~/.ksl/bundles/` for a JAR whose [bundleId] matches, then prompts
 *  the user to *Locate JAR…* if none match.  See the Scenario app's
 *  workflow design document for the full reconciliation sequence.
 *
 *  [paths] is a list of candidate hint paths.  Entries may be
 *  absolute, workspace-relative, or contain `~` for the user's home
 *  directory.  Path expansion and normalisation are the consumer's
 *  responsibility; this type stores the strings verbatim as the user
 *  supplied them.
 *
 *  @property paths      ordered list of candidate filesystem paths for
 *                       the bundle JAR; may be empty (e.g. when the
 *                       authoring user dropped a JAR via the file
 *                       picker and didn't record a stable path)
 *  @property bundleId   the `KSLModelBundle.bundleId` of the bundle
 *                       this reference targets; must be non-blank
 */
@Serializable
data class BundleRef(
    val paths: List<String> = emptyList(),
    val bundleId: String
) {
    init {
        require(bundleId.isNotBlank()) { "bundleId must be non-blank" }
        require(paths.all { it.isNotBlank() }) {
            "every entry in paths must be non-blank when present"
        }
    }
}
