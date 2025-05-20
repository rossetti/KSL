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
import ksl.utilities.random.rng.RNStreamProviderIfc

/**
 *  Implements the acceptance/rejection algorithm for uni-variate distributions.
 *  The user must supply a continuous distribution that acts as the proposal distribution
 *  and the PDF of the distribution from which random variates will be generated.
 *  The two distributions must be domain compatible. The proposal distribution's domain
 *  must wholly contain the domain of the PDF from which random variates will be generated.
 *  If the target PDF's domain is not within the proposal distribution's domain then
 *  all proposed values would be rejected.
 *
 *  Since the proposal distribution may generate values outside the domain of the target PDF
 *  it is essential that the PDF function return 0.0 for any value of x that is not
 *  within its domain.
 *
 * @param proposalDistribution the proposal distribution for generating variates that could be rejected
 * @param majorizingConstant the majorizing constant for the rejection process
 * @param pdf the PDF from which random variates are needed
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name an optional name
 */
class AcceptanceRejectionRV @JvmOverloads constructor(
    val proposalDistribution: ContinuousDistributionIfc,
    val majorizingConstant: Double,
    val pdf: PDFIfc,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc  = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : RVariable(streamNum, streamProvider, name) {
    init {
        require(majorizingConstant > 0.0) { "The majorizing constant must be greater than 0.0" }
        require(proposalDistribution.domain().contains(pdf.domain())) {"The supplied PDF domain is not contained in the domain of the proposal distribution"}
     }

    private val rVariable: RVariableIfc = proposalDistribution.randomVariable(this.streamNumber, streamProvider)
    private val domain = pdf.domain()

    override fun generate(): Double {
        var w: Double
        var u: Double
        do {
            w = rVariable.value
            u = rnStream.randU01()
        } while (!domain.contains(w) || ( u * majorizingConstant * proposalDistribution.pdf(w) > pdf.pdf(w)))
        return w
    }

    override fun instance(
        streamNum: Int,
        rnStreamProvider: RNStreamProviderIfc,
    ): AcceptanceRejectionRV {
        return AcceptanceRejectionRV(proposalDistribution, majorizingConstant, pdf, streamNum, rnStreamProvider, name)
    }

}