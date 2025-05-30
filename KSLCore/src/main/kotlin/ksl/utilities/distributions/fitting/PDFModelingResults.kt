package ksl.utilities.distributions.fitting

import ksl.utilities.moda.AdditiveMODAModel
import ksl.utilities.random.rvariable.RVParametersTypeIfc
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.statistic.Statistic
import org.jetbrains.kotlinx.dataframe.AnyFrame

/**
 *  Holds all the results from the PDF modeling process.
 *
 *  The captured evaluation model permits the translation
 *  of results to data frame representations.
 */
data class PDFModelingResults(
    val estimationResults: List<EstimationResult>,
    val scoringResults: List<ScoringResult>,
    val evaluationModel: AdditiveMODAModel
) {
    val resultsSortedByScoring: List<ScoringResult>
        get() = scoringResults.sorted()

    /**
     *  The results and their overall rank based on the value
     *  scoring model.
     */
    fun resultsAndRanksByScore() : Map<ScoringResult, Int> {
        val resultsByScoring = resultsSortedByScoring
        val map = mutableMapOf<ScoringResult, Int>()
        for(sr in scoringResults){
            map[sr] = resultsByScoring.indexOf(sr) + 1
        }
        return map
    }

    /**
     *  The top result according to the scoring evaluation model.
     */
    val topResultByScore: ScoringResult
        get() = resultsSortedByScoring.first()

    /**
     *  The top result according to the scoring evaluation model
     *  specified as the type of random variable.
     */
    val topRVTypeByScore: RVParametersTypeIfc
        get() = topResultByScore.rvType

    /**
     *  The results sorted by the average ranking of the metrics
     */
    val resultsSortedByAvgRanking: List<ScoringResult>
        get() = scoringResults.sortedBy { it.averageRanking }

    /**
     *  Display all the fitting results based on the results
     *  sorted by scoring. Shows the fit distribution plots
     *  and the goodness of fit results for every distribution in
     *  the order recommended.
     */
    fun displayAllFittingResults(){
        for (result in resultsSortedByScoring){
            result.displayFittingResults()
        }
    }

    /**
     *  The results and their overall rank based on the average of their
     *  metric ranks
     */
    fun resultsAndRanksByAvgRanking() : Map<ScoringResult, Int> {
        val resultsByScoring = resultsSortedByAvgRanking
        val map = mutableMapOf<ScoringResult, Int>()
        for(sr in scoringResults){
            map[sr] = resultsByScoring.indexOf(sr) + 1
        }
        return map
    }

    /**
     *  The top result based on the average ranking of metrics
     */
    val topResultByRanking : ScoringResult
        get() = resultsSortedByAvgRanking.first()

    /**
     *  The top result according to the average ranking of the metrics
     *  specified as the type of random variable.
     */
    val topRVTypeByRanking: RVParametersTypeIfc
        get() = topResultByRanking.rvType

    /**
     *  Returns the scores in the form of a data frame.
     *  The first column is the distributions that were evaluated.
     *  Subsequent columns represent the scoring model results for
     *  each distribution.
     */
    fun scoresAsDataFrame() : AnyFrame {
        return evaluationModel.alternativeScoresAsDataFrame("Distributions")
    }

    /**
     *  Returns the metric in the form of a data frame.
     *  The first column is the distributions that were evaluated.
     *  Subsequent columns represent the metric results for
     *  each distribution including the overall metric value. The rows are sorted by overall value.
     */
    fun metricsAsDataFrame() : AnyFrame {
        return evaluationModel.alternativeValuesAsDataFrame("Distributions")
    }

    /**
     *  Returns a data frame with the first column being the distributions
     *  by name, a column of ranks for each metric for each distribution.
     *  The metric ranking columns are labeled as "${metric.name}_Rank"
     *  @param rankingMethod provides the type of ranking. By default, it is ordinal.
     */
    fun ranksAsDataFrame(
        rankingMethod: Statistic.Companion.Ranking = evaluationModel.defaultRankingMethod
    ) : AnyFrame {
        return evaluationModel.alternativeRanksAsDataFrame("Distributions", rankingMethod)
    }

    /**
     *  The PDF modeling process estimates the parameters for a set of
     *  parameter estimators.  Each estimator is unique in the set of estimators
     *  that are processed. Each estimator produces scores on its quality of fit
     *  for the distribution. The scores are combined into an overall score for the
     *  estimator.
     *
     *  The key thing to remember is that different estimators can be supplied
     *  to estimate the parameters from the same distribution family. This is because
     *  there can be many different algorithms to estimate the parameters associated with
     *  some distribution. For example, the gamma distribution's parameters can be
     *  estimated using a method of moments algorithm or a maximum likelihood estimation
     *  algorithm. Thus, the scoring results are about the estimators, not about specific
     *  distributions unless the set of evaluated parameter estimators do not have multiple
     *  algorithms for the same distribution.
     *
     *  Therefore, the ranking of the scoring model is about the estimator used.
     *
     *  A rank of 0, means that the estimation result [estimationResult] was not
     *  found in the results. The returned rank {1, 2, ...} is dependent on
     *  the number of estimators that were fit within the PDF modeling
     *  process. A rank of 1, means that the estimator associated
     *  with the estimation result was the recommended
     *  estimator (top ranked) based on the scoring model. Thus, lower
     *  ranks imply better estimation fit.
     */
    fun rank(estimationResult: EstimationResult): Int {
        // get the ranked results, save to do sort only once
        val rankedResults = resultsSortedByScoring
        // find the result for the specified estimation result
        val scoringResult: ScoringResult? = rankedResults.find { it.estimationResult == estimationResult }
        return if (scoringResult == null) {
            0
        } else {
            rankedResults.indexOf(scoringResult) + 1
        }
    }

    /**
     *  A rank of 0, means that the random variable type [rvType] was not
     *  found in the results. The returned rank {1, 2, ...} is dependent on
     *  the number of distributions that were fit within the PDF modeling
     *  process. A rank of 1, means that the distribution was the recommended
     *  distribution (top ranked) based on the scoring model. Thus, lower
     *  ranks imply better distribution fit.  The returned rank is the first
     *  instance of the random variable type.
     */
    fun rank(rvType: RVType): Int {
        // get the ranked results, save to do sort only once
        val rankedResults = resultsSortedByScoring
        // find the result for the specified random variable type
        val scoringResult: ScoringResult? = rankedResults.find { it.rvType == rvType }
        return if (scoringResult == null) {
            0
        } else {
            rankedResults.indexOf(scoringResult) + 1
        }
    }
}