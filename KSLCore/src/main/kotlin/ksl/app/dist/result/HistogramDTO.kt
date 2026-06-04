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
 * Wire-safe mirror of one histogram bin, sourced from the engine's
 * `HistogramBinData`. Lets a client render the same binning the engine used.
 */
@Serializable
data class HistogramBinDTO(
    val binNum: Int,
    val binLabel: String,
    val lowerLimit: Double,
    val upperLimit: Double,
    val count: Double,
    val cumCount: Double,
    val proportion: Double,
    val cumProportion: Double
)

/**
 * Wire-safe histogram: the ordered bins plus the under/overflow counts.
 * Populated from `HistogramIfc.histogramData()` by the result extractor.
 */
@Serializable
data class HistogramDTO(
    val bins: List<HistogramBinDTO>,
    val underFlowCount: Double,
    val overFlowCount: Double
)
