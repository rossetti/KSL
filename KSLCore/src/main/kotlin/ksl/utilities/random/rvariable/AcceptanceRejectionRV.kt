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