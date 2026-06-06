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
import ksl.utilities.statistic.IntegerFrequency

/**
 * Wire-safe mirror of one integer-frequency cell, sourced from the engine's
 * `ksl.utilities.statistic.FrequencyData`. Lets a client render the same
 * frequency distribution the engine computed for the discrete data.
 */
@Serializable
data class IntegerFrequencyCellDTO(
    val value: Int,
    val count: Double,
    val cumCount: Double,
    val proportion: Double,
    val cumProportion: Double,
    /** Optional cell label (e.g., the distribution-family name for a bootstrap family frequency). */
    val cellLabel: String = ""
)

/**
 * Wire-safe integer-frequency distribution for a discrete data series — the
 * discrete-path counterpart to `HistogramDTO`. Populated from
 * `IntegerFrequency.frequencyData()` by the result extractor; null on the
 * continuous path.
 */
@Serializable
data class IntegerFrequencyDTO(
    val cells: List<IntegerFrequencyCellDTO>
)

/** Maps a live [IntegerFrequency] to its wire-safe [IntegerFrequencyDTO]. */
fun IntegerFrequency.toIntegerFrequencyDTO(): IntegerFrequencyDTO =
    IntegerFrequencyDTO(
        frequencyData().map {
            IntegerFrequencyCellDTO(it.value, it.count, it.cum_count, it.proportion, it.cumProportion, it.cellLabel)
        }
    )
