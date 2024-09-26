package ksl.utilities.distributions.fitting.scoring

import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.distributions.fitting.EstimationResult
import ksl.utilities.moda.Score

class ParameterErrorModel(

) : PDFScoringModel("PMSE", allowLowerLimitAdjustment = true, allowUpperLimitAdjustment = true) {

    override fun score(data: DoubleArray, cdf: ContinuousDistributionIfc): Score {
        TODO("Not yet implemented")
    }

    override fun score(result: EstimationResult): Score {
        val parameters = result.parameters
        return if (parameters == null){
            metric.badScore()
        } else {
            val bsr = result.bootStrapResults()
            return Score(metric, bsr.totalMSE)
        }
    }

    override fun newInstance(): PDFScoringModel {
        return ParameterErrorModel()
    }

}