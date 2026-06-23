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
 * outcome. `family + parameters + shift` is everything a distribution-capable
 * client needs to reconstruct the fitted distribution and build any
 * fit-quality plot from its own raw data.
 *
 * The continuous side populates the headline MODA fields (`weightedValue`,
 * `averageRanking`, `firstRankCount` — the engine's `OverallValueData`); the
 * full per-metric MODA breakdown lives once in the report's `ModaResultDTO`,
 * joined by `displayName == alternative`. The discrete side populates
 * `chiSquaredPValue` (a convenience mirror of `goodnessOfFit?.chiSquaredPValue`,
 * used as the discrete ranking key).
 *
 * `goodnessOfFit` and `bootstrap` are populated in a later phase by the
 * result extractor; they are null until then.
 */
@Serializable
data class DistributionFitDTO(
    val rank: Int,
    val familyId: String,
    val estimatorId: String,
    val rvTypeName: String,
    val displayName: String,
    val parameters: Map<String, Double>,
    val numberOfParameters: Int,
    val success: Boolean,
    val message: String? = null,
    val shift: Double = 0.0,
    // headline MODA numbers (continuous; per-metric detail is in ModaResultDTO):
    val weightedValue: Double? = null,
    val averageRanking: Double? = null,
    val firstRankCount: Int? = null,
    // discrete ranking key (mirrors goodnessOfFit?.chiSquaredPValue):
    val chiSquaredPValue: Double? = null,
    // populated by the result extractor in a later phase:
    val goodnessOfFit: GoodnessOfFitDTO? = null,
    val bootstrap: List<BootstrapEstimateDTO>? = null
)
