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
 * Compact, wire-safe summary of the data series that was fit.
 *
 * `shift` is the left shift PDFModeler applied during automatic shifting;
 * zero when no shift was applied (either because automatic shifting was
 * disabled or because the data did not warrant one).
 */
@Serializable
data class DataSummary(
    val n: Int,
    val min: Double,
    val max: Double,
    val average: Double,
    val standardDeviation: Double,
    val shift: Double
)
