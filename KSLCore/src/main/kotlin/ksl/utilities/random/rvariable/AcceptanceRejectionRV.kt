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
    val proposalDistribution: ContinuousDistributionIfc,
    val majorizingConstant: Double,
    val pdf: PDFIfc,
    rnStream: RNStreamIfc = KSLRandom.nextRNStream()
) : RVariable(rnStream) {
    init {
        require(majorizingConstant > 0.0) { "The majorizing constant must be greater than 0.0" }
        require(proposalDistribution.domain().contains(pdf.domain())) {"The supplied PDF domain is not contained in the domain of the proposal distribution"}
 //       require(proposalDistribution.domain() == pdf.domain()) { "The domains of the two distributions are not equal" }
    }

    private val rVariable: RVariableIfc = proposalDistribution.randomVariable(rnStream)

    override fun generate(): Double {
        var w: Double
        var u: Double
        do {
            w = rVariable.value
            u = rVariable.rnStream.randU01()
        } while (u * majorizingConstant * proposalDistribution.pdf(w) > pdf.pdf(w))
        return w
    }

    override fun instance(stream: RNStreamIfc): RVariableIfc {
        return AcceptanceRejectionRV(proposalDistribution, majorizingConstant, pdf, stream)
    }
}