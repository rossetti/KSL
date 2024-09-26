package ksl.utilities.distributions.fitting.scoring

import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.distributions.fitting.EstimationResult
import ksl.utilities.moda.Score
import kotlin.math.sqrt

/**
 *  The scoring is based on bootstrapping the mean squared error of the
 *  estimated parameter. The score is the square root of the total mean squared
 *  error based on the sum over the parameters.
 */
class ParameterMSEModel : PDFScoringModel("PMSE") {

    override fun score(data: DoubleArray, cdf: ContinuousDistributionIfc): Score {
        // this will never be called
        return metric.badScore()
    }

    override fun score(result: EstimationResult): Score {
        val parameters = result.parameters
        return if (parameters == null){
            metric.badScore()
        } else {
            val bsr = result.bootStrapResults()
            return Score(metric, sqrt(bsr.totalMSE))
        }
    }

    override fun newInstance(): PDFScoringModel {
        return ParameterMSEModel()
    }

}