/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.utilities.random.rvariable

import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.distributions.PDFIfc
import ksl.utilities.random.rng.RNStreamIfc

/**
 *  Implements the acceptance/rejection algorithm for uni-variate distributions.
 *  The user must supply a continuous distribution that acts as the proposal distribution
 *  and the PDF of the distribution from which random variates will be generated.
 *  The two distributions must be domain compatible.
 */
class AcceptanceRejectionRV(
    proposalDistribution: ContinuousDistributionIfc,
    thePDF: PDFIfc,
    rnStream: RNStreamIfc = KSLRandom.nextRNStream()
) : RVariable(rnStream) {
    init {
        require(proposalDistribution.domain() == thePDF.domain()) { "The domains of the two distributions are not equal" }
    }

    private val distribution: ContinuousDistributionIfc = proposalDistribution
    private val pdf: PDFIfc = thePDF
    private val rVariable: RVariableIfc = proposalDistribution.randomVariable(rnStream)

    override fun generate(): Double {
        var w: Double
        var u: Double
        do {
            w = rVariable.value
            u = rVariable.rnStream.randU01()
        } while (u * distribution.pdf(w) > pdf.pdf(w))
        return w
    }

    override fun instance(stream: RNStreamIfc): RVariableIfc {
        return AcceptanceRejectionRV(distribution, pdf, stream)
    }
}