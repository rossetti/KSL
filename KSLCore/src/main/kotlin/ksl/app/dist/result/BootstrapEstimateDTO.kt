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
 * Wire-safe bootstrap summary for one estimated parameter. The engine
 * performs the resampling and returns only these summaries — never the raw
 * replicate arrays. Three confidence intervals are reported (normal, basic,
 * percentile), each as an explicit lower/upper pair.
 *
 * Populated in a later phase, only when the configuration requests bootstrap.
 */
@Serializable
data class BootstrapEstimateDTO(
    val parameterName: String,
    val originalEstimate: Double,
    val bootstrapAverage: Double,
    val bias: Double,
    val mse: Double,
    val stdError: Double,
    val numBootstraps: Int,
    val ciLevel: Double,
    val normalCILower: Double,
    val normalCIUpper: Double,
    val basicCILower: Double,
    val basicCIUpper: Double,
    val percentileCILower: Double,
    val percentileCIUpper: Double
)
