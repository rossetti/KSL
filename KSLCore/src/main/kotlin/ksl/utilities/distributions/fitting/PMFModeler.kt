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

import ksl.utilities.distributions.InverseCDFIfc

class PMFModeler {


    companion object {

        fun equalizedCDFBreakPoints(sampleSize: Int, inverse: InverseCDFIfc): DoubleArray {
            var bp = PDFModeler.equalizedCDFBreakPoints(sampleSize, inverse)
            // there could be duplicate values, remove them
            val set = LinkedHashSet<Double>(bp.asList())
            // convert back to array
            bp = set.toDoubleArray()
            // make sure that they are sorted from smallest to largest
            bp.sort()
            return bp
        }
    }
}