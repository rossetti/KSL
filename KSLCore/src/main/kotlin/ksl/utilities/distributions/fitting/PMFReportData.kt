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

package ksl.utilities.distributions.fitting

import ksl.utilities.statistic.IntegerFrequency
import ksl.utilities.statistic.StatisticIfc

/**
 * The sample-level data a discrete distribution-fitting report needs for its
 * exploratory-data sections (statistics, integer-frequency distribution,
 * dispersion analysis).
 *
 * [PMFModeler] implements this directly; a reconstructed/serialized result can
 * implement it too, so the same report extensions render from either source.
 */
interface PMFData {
    val statistics: StatisticIfc
    val data: IntArray
    val frequency: IntegerFrequency
    val hasZeroes: Boolean
    val hasNegatives: Boolean
}

/**
 * The per-fit data a discrete fitting report needs: the chi-squared
 * goodness-of-fit object and the integer data used for the empirical-vs-
 * theoretical PMF comparison plot.
 */
interface PMFFitData {
    val goodnessOfFit: DiscretePMFGoodnessOfFit
    val data: IntArray
}
