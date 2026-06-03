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

package ksl.app.dist.result

import kotlinx.serialization.Serializable
import ksl.app.dist.config.DistributionKind

/**
 * Wire-safe machine result for one distribution-fitting job over one
 * dataset, parallel to the run-result DTOs in `ksl.app.session`. CLIs,
 * REST hosts, and agents consume this directly; human-facing reports are
 * built in-process by a separate reporting layer and are not serialized.
 *
 * `recommendedFamilyId` is the family ID of the top-ranked successful fit,
 * or null when no estimator produced a usable result. `fits` is sorted
 * best-first (rank 1 is the recommended fit) with failed attempts at the
 * bottom in undefined relative order.
 */
@Serializable
data class FitReport(
    val datasetName: String,
    val kind: DistributionKind,
    val dataSummary: DataSummary,
    val fits: List<DistributionFitSummary>,
    val recommendedFamilyId: String?
)
