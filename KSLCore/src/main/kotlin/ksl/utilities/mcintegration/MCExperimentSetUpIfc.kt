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

package ksl.utilities.mcintegration

interface MCExperimentSetUpIfc {
    /**
     * the desired confidence level
     */
    var confidenceLevel: Double

    /**
     * the initial sample size for pilot simulation
     */
    var initialSampleSize: Int

    /**
     * the maximum number of samples permitted
     */
    var maxSampleSize: Long

    /**
     * the desired half-width bound for the experiment
     */
    var desiredHWErrorBound: Double

    /**
     * determines whether the reset stream option is on (true) or off (false)
     */
    var resetStreamOption : Boolean

    /**
     *  the number of micro replications to perform
     */
    var microRepSampleSize: Int
}