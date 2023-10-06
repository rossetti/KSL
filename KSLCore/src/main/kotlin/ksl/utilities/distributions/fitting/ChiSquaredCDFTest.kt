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

import ksl.utilities.distributions.DistributionFunctionIfc


/**
 *  The purpose of this class is to automate the chi-squared goodness of fit test
 *  for a supplied cumulative distribution function.
 */
class ChiSquaredCDFTest(
    data: DoubleArray,
    df: DistributionFunctionIfc,
) {

    private val dist = df
    

    //TODO constructor for discrete, for continuous?
    // automatically determine the break points, but allow them to be changed
}