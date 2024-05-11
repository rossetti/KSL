package ksl.utilities.distributions.fitting.scoring

import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.moda.Score
import ksl.utilities.statistic.Histogram
import kotlin.math.sqrt

/**
 *  This scoring model represents the Mallows L2 distance between the
 *  theoretical probabilities and the observed probabilities based
 *  on a histogram of the data. The break points for the histogram
 *  are specified by PDFModeler.equalizedCDFBreakPoints()
 *
 *  The Mallows L2 distance is the square root of the mean squared
 *  error for the theoretical versus the observed probabilities.
 *
 *  See: http://luthuli.cs.uiuc.edu/~daf/courses/Opt-2017/Combinatorialpapers/EMD.pdf
 *
 */
class MallowsL2ScoringModel : PDFScoringModel("MallowsL2") {

    override fun score(data: DoubleArray, cdf: ContinuousDistributionIfc): Score {
        if (data.isEmpty()){
            return Score(this, Double.MAX_VALUE, true)
        }
        var bp = PDFModeler.equalizedCDFBreakPoints(data.size, cdf)
        // make sure that they are unique
        bp = bp.toSet().toDoubleArray()
        val domain = cdf.domain()
        bp = Histogram.addLowerLimit(domain.lowerLimit, bp)
        bp = Histogram.addUpperLimit(domain.upperLimit, bp)
        val h = Histogram(bp)
        h.collect(data)
        val predicted =  PDFModeler.binProbabilities(h.bins, cdf)
        val observed = h.binFractions
        val n = predicted.size.coerceAtMost(observed.size)
        if (n == 0){
            return Score(this, Double.MAX_VALUE, true)
        }
        var sum = 0.0
        for (i in 0.until(n)) {
            sum = sum + (predicted[i] - observed[i]) * (predicted[i] - observed[i])
        }
        val mL2 = sqrt(sum/n.toDouble())
        return Score(this, mL2,true)
    }

    override fun newInstance(): MallowsL2ScoringModel {
        return MallowsL2ScoringModel()
    }
}