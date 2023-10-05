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

        /**
         *  This function is similar in purpose to the similarly named function in PDFModeler.
         *  The primary difference is that this function ensures that the returned break points
         *  are unique because a set of probability may map to the same value for a discrete
         *  distribution.
         *
         *  Computes breakpoints for the distribution that ensures (approximately) that
         *  the expected number of observations within the intervals defined by the breakpoints
         *  will be equal. That is, the probability associated with each interval is roughly
         *  equal. In addition, the expected number of observations will be approximately
         *  greater than or equal to 5.  There will be at least two breakpoints and thus at least
         *  3 intervals defined by the breakpoints.
         *
         *  If the sample size [sampleSize] is less than 15, then the approximate
         *  expected number of observations within the intervals may not be greater than or equal to 5.
         *  Note that the returned break points do not consider the range of the CDF
         *  and may require end points to be added to the beginning or end of the array
         *  to adjust for the range of the CDF.
         *
         *  The returned break points are based on the natural domain of the implied
         *  CDF and do not account for any shift that may be needed during the modeling
         *  process.
         */
        fun equalizedCDFBreakPoints(sampleSize: Int, inverse: InverseCDFIfc): DoubleArray {
            var bp = PDFModeler.equalizedCDFBreakPoints(sampleSize, inverse)
            // there could be duplicate values, remove them by forcing them into a set
            val set = LinkedHashSet<Double>(bp.asList())
            // convert back to array
            bp = set.toDoubleArray()
            // make sure that they are sorted from smallest to largest
            bp.sort()
            return bp
        }
    }
}