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

/**
 * Wire-safe, asymmetry-aware result for one (estimator, distribution-family)
 * outcome inside a fit report.
 *
 * The continuous side populates the MODA-scoring fields (`weightedValue`,
 * `averageRanking`, `firstRankCount`) and leaves `chiSquaredPValue` null.
 * The discrete side does the opposite. This shape lets a uniform DTO
 * carry both kinds without forcing PMF results through scoring it does not
 * compute.
 *
 * Failed estimators are included with `success = false`, sorted to the
 * bottom of the ranked list, so the front-end can show "tried but
 * failed" without losing the attempt.
 */
@Serializable
data class DistributionFitSummary(
    val rank: Int,
    val familyId: String,
    val estimatorId: String,
    val displayName: String,
    val parameters: Map<String, Double>,
    val success: Boolean,
    val message: String? = null,
    val weightedValue: Double? = null,
    val averageRanking: Double? = null,
    val firstRankCount: Int? = null,
    val chiSquaredPValue: Double? = null
)
