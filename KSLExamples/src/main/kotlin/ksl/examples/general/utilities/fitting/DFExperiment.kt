package ksl.examples.general.utilities.fitting

import ksl.utilities.distributions.fitting.*
import ksl.utilities.distributions.fitting.estimators.*
import ksl.utilities.distributions.fitting.scoring.*
import ksl.utilities.io.KSL
import ksl.utilities.moda.MetricIfc
import ksl.utilities.moda.Score
import ksl.utilities.random.rvariable.RVParametersTypeIfc
import ksl.utilities.statistic.Classification
import ksl.utilities.statistic.ErrorMatrix
import ksl.utilities.statistic.Statistic
import java.nio.file.Path
import kotlin.time.TimeSource

/**
 * @param name the name of the experiment for file tagging
 * @param cases the cases to execute in the experiment
 * @param estimators the set of estimators to include in the experiments. The
 * default is specified by [DFExperiment.defaultEstimators]
 * @param scoringModels the set of scoring models to include in the experiments.
 * The default is specified by [DFExperiment.defaultScoringModels].
 * @param outputDirectory the directory to store the results. By default, this
 * will be a subdirectory of kslOutput with the name of the experiment.
 */
class DFExperiment(
    val name: String,
    val cases: List<DFTestCase>,
    estimators: Set<ParameterEstimatorIfc> = defaultEstimators,
    scoringModels: Set<PDFScoringModel> = defaultScoringModels,
    val outputDirectory: Path = KSL.createSubDirectory(name)
) {
    val resultsDb: ResultsDb = ResultsDb(dbName = "${name}_ResultsDb", dbDirectory = outputDirectory)

    var messageOutput: Boolean = false

    /**
     *  Can be supplied to provide output after each case is executed.
     */
    var byCaseOutput: ((DFTestCase, Int) -> String)? = this::messageOutput

    private fun messageOutput(dfTestCase: DFTestCase, sampleSize: Int): String {
        return "ID = ${dfTestCase.case.caseID}; label = ${dfTestCase.case.label}; sample size = ${sampleSize}; num samples = ${dfTestCase.case.numSamples} "

    }

    private val myEstimators: Set<ParameterEstimatorIfc> = estimators
        get() {
            val set = mutableSetOf<ParameterEstimatorIfc>()
            for (element in field) {
                set.add(element)
            }
            return set
        }

    private val myScoringModels: Set<PDFScoringModel> = scoringModels
        get() {
            val set = mutableSetOf<PDFScoringModel>()
            for (element in field) {
                set.add(element.newInstance())
            }
            return set
        }

    private val myMetricErrorMatrix = mutableMapOf<String, ErrorMatrix>()

    private val myOverallScoringErrorMatrix = mutableMapOf<String, ErrorMatrix>()

    fun runCases() {
        myMetricErrorMatrix.clear()
        myOverallScoringErrorMatrix.clear()
        for (case in cases) {
            val mark = TimeSource.Monotonic.markNow()
            saveCaseToDb(case)
            for (sampleSize in case.sampleSizes) {
                runCase(case, sampleSize)
                if (messageOutput) {
                    println(byCaseOutput?.invoke(case, sampleSize))
                }
            }
            if (messageOutput){
                println("Case: elapsed time = ${mark.elapsedNow()}")
            }
        }
    }

    private fun runCase(dfTestCase: DFTestCase, sampleSize: Int) {
        // run each case for the specified number of samples
        myMetricErrorMatrix.clear()
        myOverallScoringErrorMatrix.clear()
        val caseRankingData = mutableListOf<CaseScoringResult>()
        for (i in 1..dfTestCase.case.numSamples) {
            val data = dfTestCase.rv.sample(sampleSize)
            val pdfModeler = PDFModeler(data, scoringModels = myScoringModels)
            // score evaluation process
            val pdfModelingResults = pdfModeler.estimateAndEvaluateScores(
                estimators = myEstimators,
                automaticShifting = dfTestCase.automaticShifting,
            )
            // set up for metric error tabulation
            if (myMetricErrorMatrix.isEmpty()) {
                val metrics = pdfModelingResults.topResultByRanking.metrics
                for (metric in metrics) {
//                    println("Making ErrorMatrix for metric ${metric.name} for case = ${dfTestCase.case.caseID} for sample size = $sampleSize")
                    myMetricErrorMatrix[metric.name] = ErrorMatrix()
                }
            }
            if (myOverallScoringErrorMatrix.isEmpty()){
                myOverallScoringErrorMatrix["Rank By Score"] = ErrorMatrix()
                myOverallScoringErrorMatrix["Rank By Avg Rank"] = ErrorMatrix()
            }
            saveStatistics(dfTestCase.case.caseID, sampleSize, i, Statistic(data))
            val rankData = saveFittingResults(dfTestCase, i, pdfModelingResults)
            caseRankingData.addAll(rankData)
            // the metric error tabulation for this sample has been completed (internally) and stored
            // in myMetricErrorMatrix, need to continue across all samples
            // these are the ranking results for (caseID, sampleSize, sampleID)
        }
        // we have finished all numSamples of size (sample size) of caseID
        // need to save metric performance across the samples for the (caseID, sampleSize) combination
        saveMetricPerformanceToDb(dfTestCase, sampleSize)
        saveOverallRecommendationPerformanceToDb(dfTestCase, sampleSize)
    }

    private fun saveOverallRecommendationPerformanceToDb(
        dfTestCase: DFTestCase,
        sampleSize: Int
    ) {
        val list = mutableListOf<CaseMetricErrorMatrixData>()
        for((name, em) in myOverallScoringErrorMatrix){
            val emd = em.asErrorMatrixData()
            val caseMetricError = CaseMetricErrorMatrixData()
            caseMetricError.caseID = dfTestCase.case.caseID
            caseMetricError.sampleSize = sampleSize
            caseMetricError.metricName = name
            caseMetricError.numTP = emd.numTP
            caseMetricError.numFN = emd.numFN
            caseMetricError.numFP = emd.numFP
            caseMetricError.numTN = emd.numTN
            caseMetricError.numP = emd.numP
            caseMetricError.numN = emd.numN
            caseMetricError.total = emd.total
            caseMetricError.numPP = emd.numPP
            caseMetricError.numPN = emd.numPN
            caseMetricError.prevalence = emd.prevalence
            caseMetricError.accuracy = emd.accuracy
            caseMetricError.truePositiveRate = emd.truePositiveRate
            caseMetricError.falseNegativeRate = emd.falseNegativeRate
            caseMetricError.falsePositiveRate = emd.falsePositiveRate
            caseMetricError.trueNegativeRate = emd.trueNegativeRate
            caseMetricError.falseOmissionRate = emd.falseOmissionRate
            caseMetricError.positivePredictiveValue = emd.positivePredictiveValue
            caseMetricError.falseDiscoveryRate = emd.falseDiscoveryRate
            caseMetricError.falseDiscoveryRate = emd.falseDiscoveryRate
            caseMetricError.negativePredictiveValue = emd.negativePredictiveValue
            caseMetricError.positiveLikelihoodRatio = emd.positiveLikelihoodRatio
            caseMetricError.negativeLikelihoodRatio = emd.negativeLikelihoodRatio
            caseMetricError.markedness = emd.markedness
            caseMetricError.diagnosticOddsRatio = emd.diagnosticOddsRatio
            caseMetricError.balancedAccuracy = emd.balancedAccuracy
            caseMetricError.f1Score = emd.f1Score
            caseMetricError.fowlkesMallowsIndex = emd.fowlkesMallowsIndex
            caseMetricError.mathhewsCorrelationCoefficient = emd.mathhewsCorrelationCoefficient
            caseMetricError.threatScore = emd.threatScore
            caseMetricError.informedness = emd.informedness
            caseMetricError.prevalenceThreshold = emd.prevalenceThreshold
            list.add(caseMetricError)
        }
        resultsDb.saveErrorMatrixData(list)
    }

    private fun saveMetricPerformanceToDb(dfTestCase: DFTestCase, sampleSize: Int) {
        val list = mutableListOf<CaseMetricErrorMatrixData>()
        for ((metric, em) in myMetricErrorMatrix) {
            val emd = em.asErrorMatrixData()
            val caseMetricError = CaseMetricErrorMatrixData()
            caseMetricError.caseID = dfTestCase.case.caseID
            caseMetricError.sampleSize = sampleSize
            caseMetricError.metricName = metric
            caseMetricError.numTP = emd.numTP
            caseMetricError.numFN = emd.numFN
            caseMetricError.numFP = emd.numFP
            caseMetricError.numTN = emd.numTN
            caseMetricError.numP = emd.numP
            caseMetricError.numN = emd.numN
            caseMetricError.total = emd.total
            caseMetricError.numPP = emd.numPP
            caseMetricError.numPN = emd.numPN
            caseMetricError.prevalence = emd.prevalence
            caseMetricError.accuracy = emd.accuracy
            caseMetricError.truePositiveRate = emd.truePositiveRate
            caseMetricError.falseNegativeRate = emd.falseNegativeRate
            caseMetricError.falsePositiveRate = emd.falsePositiveRate
            caseMetricError.trueNegativeRate = emd.trueNegativeRate
            caseMetricError.falseOmissionRate = emd.falseOmissionRate
            caseMetricError.positivePredictiveValue = emd.positivePredictiveValue
            caseMetricError.falseDiscoveryRate = emd.falseDiscoveryRate
            caseMetricError.falseDiscoveryRate = emd.falseDiscoveryRate
            caseMetricError.negativePredictiveValue = emd.negativePredictiveValue
            caseMetricError.positiveLikelihoodRatio = emd.positiveLikelihoodRatio
            caseMetricError.negativeLikelihoodRatio = emd.negativeLikelihoodRatio
            caseMetricError.markedness = emd.markedness
            caseMetricError.diagnosticOddsRatio = emd.diagnosticOddsRatio
            caseMetricError.balancedAccuracy = emd.balancedAccuracy
            caseMetricError.f1Score = emd.f1Score
            caseMetricError.fowlkesMallowsIndex = emd.fowlkesMallowsIndex
            caseMetricError.mathhewsCorrelationCoefficient = emd.mathhewsCorrelationCoefficient
            caseMetricError.threatScore = emd.threatScore
            caseMetricError.informedness = emd.informedness
            caseMetricError.prevalenceThreshold = emd.prevalenceThreshold
            list.add(caseMetricError)
        }
        resultsDb.saveErrorMatrixData(list)
    }

    private fun saveCaseToDb(dfTestCase: DFTestCase) {
        resultsDb.saveCase(dfTestCase)
        resultsDb.saveCaseParameters(dfTestCase)
    }

    private fun saveStatistics(caseID: Int, sampleSize: Int, sampleID: Int, statistic: Statistic) {
        resultsDb.saveStatistics(caseID, sampleSize, sampleID, statistic)
    }

    private fun saveFittingResults(
        dfTestCase: DFTestCase,
        sampleID: Int,
        results: PDFModelingResults
    ): List<CaseScoringResult> {
        val list = mutableListOf<CaseScoringResult>()
        // need to get estimation results, this will have the parameters
        val paramData = captureParameters(dfTestCase, sampleID, results.estimationResults)
        list.addAll(paramData)
        // need to capture the scoring evaluation
        val scoreData = captureScores(dfTestCase.case.caseID, sampleID, results.scoringResults)
        list.addAll(scoreData)
        // need to capture ranks
        val rankData = captureRanks(dfTestCase, sampleID, results)
        list.addAll(rankData)
        val overAllByScoreData = captureOverallRankByScoring(dfTestCase, sampleID, results)
        list.addAll(overAllByScoreData)
        val overAllByAvgRankData = captureOverallRankByAvgRanking(dfTestCase, sampleID, results)
        list.addAll(overAllByAvgRankData)
        resultsDb.saveScoringResults(list)
        return rankData
    }

    private fun captureOverallRankByScoring(
        dfTestCase: DFTestCase,
        sampleID: Int,
        results: PDFModelingResults
    ): List<CaseScoringResult> {
        val list = mutableListOf<CaseScoringResult>()
        val resultsAndRanksByScore: Map<ScoringResult, Int> = results.resultsAndRanksByScore()
        for ((sr, rank) in resultsAndRanksByScore){
            val nc = CaseScoringResult()
            nc.caseID = dfTestCase.case.caseID
            nc.sampleSize = sr.estimationResult.originalData.size
            nc.sampleID = sampleID
            nc.estimatedDistribution = sr.rvType.toString()
            nc.resultType = "Ranking"
            nc.resultName = "Rank By Score"
            nc.resultValue = rank.toDouble()
            val c = classifyCase(rank.toDouble(), dfTestCase.rvType(), sr.rvType)
            nc.classification = c.classification.name
            myOverallScoringErrorMatrix[nc.resultName]!!.collect(c)
            list.add(nc)
        }
        return list
    }

    private fun captureOverallRankByAvgRanking(
        dfTestCase: DFTestCase,
        sampleID: Int,
        results: PDFModelingResults
    ): List<CaseScoringResult> {
        val list = mutableListOf<CaseScoringResult>()
        val resultsAndRanksByScore: Map<ScoringResult, Int> = results.resultsAndRanksByAvgRanking()
        for ((sr, rank) in resultsAndRanksByScore){
            val nc = CaseScoringResult()
            nc.caseID = dfTestCase.case.caseID
            nc.sampleSize = sr.estimationResult.originalData.size
            nc.sampleID = sampleID
            nc.estimatedDistribution = sr.rvType.toString()
            nc.resultType = "Ranking"
            nc.resultName = "Rank By Avg Rank"
            nc.resultValue = rank.toDouble()
            val c = classifyCase(rank.toDouble(), dfTestCase.rvType(), sr.rvType)
            nc.classification = c.classification.name
            myOverallScoringErrorMatrix[nc.resultName]!!.collect(c)
            list.add(nc)
        }
        return list
    }

    private fun captureRanks(
        dfTestCase: DFTestCase,
        sampleID: Int,
        results: PDFModelingResults
    ): List<CaseScoringResult> {
        val list = mutableListOf<CaseScoringResult>()
        val metricRanks = metricRankByScoringResult(results)
        for ((sr, metricMap) in metricRanks) {
            for ((metric, rank) in metricMap) {
                val nc = CaseScoringResult()
                nc.caseID = dfTestCase.case.caseID
                nc.sampleSize = sr.estimationResult.originalData.size
                nc.sampleID = sampleID
                nc.estimatedDistribution = sr.rvType.toString()
                nc.resultType = "Metric Rank"
                nc.resultName = metric.name
                nc.resultValue = rank
                val c = classifyCase(rank, dfTestCase.rvType(), sr.rvType)
                nc.classification = c.classification.name
                myMetricErrorMatrix[metric.name]!!.collect(c)
                list.add(nc)
            }
        }
        return list
    }

    /**
     *   Constructs a map of maps with the key to the outer map
     *   being the scoring result  and the inner map holding the rank
     *   of the associated metric. Allows the lookup of the rank for
     *   a metric by scoring result.
     */
    private fun metricRankByScoringResult(
        results: PDFModelingResults
    ): Map<ScoringResult, Map<MetricIfc, Double>> {
        val rankingMethod = results.evaluationModel.defaultRankingMethod
        val mapOfMaps = mutableMapOf<ScoringResult, MutableMap<MetricIfc, Double>>()
        val ranksByMetric = results.evaluationModel.ranksByMetric(rankingMethod)
        val scoringResults = results.scoringResults
        for ((metric, ranks) in ranksByMetric) {
            for ((i, rank) in ranks.withIndex()) {
                if (!mapOfMaps.containsKey(scoringResults[i])) {
                    // create the inner map
                    mapOfMaps[scoringResults[i]] = mutableMapOf()
                }
                // get the inner map, it must be there
                val map = mapOfMaps[scoringResults[i]]!!
                // now fill it
                map[metric] = rank
            }
        }
        return mapOfMaps
    }

    private fun captureScores(
        caseID: Int,
        sampleID: Int,
        scoringResults: List<ScoringResult>
    ): List<CaseScoringResult> {
        val list = mutableListOf<CaseScoringResult>()
        for (sr in scoringResults) {
            for (score in sr.scores) {
                // first get the raw score
                list.add(makeScoreResult(caseID, sampleID, sr, score))
                // now get the transformed score as a value
                list.add(makeMetricResult(caseID, sampleID, sr, score))
            }
            // now get the overall evaluation metrics
            list.add(makeWeightedValueResult(caseID, sampleID, sr))
            list.add(makeFirstRankCountResult(caseID, sampleID, sr))
            list.add(makeAverageRankResult(caseID, sampleID, sr))
        }
        return list
    }

    private fun makeScoreResult(
        caseID: Int,
        sampleID: Int,
        sr: ScoringResult,
        score: Score
    ): CaseScoringResult {
        val nc = CaseScoringResult()
        nc.caseID = caseID
        nc.sampleSize = sr.estimationResult.originalData.size
        nc.sampleID = sampleID
        nc.estimatedDistribution = sr.rvType.toString()
        nc.resultType = "Metric Score"
        nc.resultName = score.metric.name
        nc.resultValue = score.value
        return nc
    }

    private fun makeMetricResult(
        caseID: Int,
        sampleID: Int,
        sr: ScoringResult,
        score: Score
    ): CaseScoringResult {
        val nc = CaseScoringResult()
        nc.caseID = caseID
        nc.sampleSize = sr.estimationResult.originalData.size
        nc.sampleID = sampleID
        nc.estimatedDistribution = sr.rvType.toString()
        nc.resultType = "Metric Value"
        nc.resultName = score.metric.name
        nc.resultValue = sr.values[score.metric]!!
        return nc
    }

    private fun makeWeightedValueResult(
        caseID: Int,
        sampleID: Int,
        sr: ScoringResult,
    ): CaseScoringResult {
        val nc = CaseScoringResult()
        nc.caseID = caseID
        nc.sampleSize = sr.estimationResult.originalData.size
        nc.sampleID = sampleID
        nc.estimatedDistribution = sr.rvType.toString()
        nc.resultType = "Overall Value"
        nc.resultName = "Weighted"
        nc.resultValue = sr.weightedValue
        return nc
    }

    private fun makeFirstRankCountResult(
        caseID: Int,
        sampleID: Int,
        sr: ScoringResult,
    ): CaseScoringResult {
        val nc = CaseScoringResult()
        nc.caseID = caseID
        nc.sampleSize = sr.estimationResult.originalData.size
        nc.sampleID = sampleID
        nc.estimatedDistribution = sr.rvType.toString()
        nc.resultType = "Ranking"
        nc.resultName = "First Count"
        nc.resultValue = sr.firstRankCount.toDouble()
        return nc
    }

    private fun makeAverageRankResult(
        caseID: Int,
        sampleID: Int,
        sr: ScoringResult,
    ): CaseScoringResult {
        val nc = CaseScoringResult()
        nc.caseID = caseID
        nc.sampleSize = sr.estimationResult.originalData.size
        nc.sampleID = sampleID
        nc.estimatedDistribution = sr.rvType.toString()
        nc.resultType = "Ranking"
        nc.resultName = "Average"
        nc.resultValue = sr.averageRanking
        return nc
    }

    private fun captureParameters(
        dfTestCase: DFTestCase,
        sampleID: Int,
        estimationResults: List<EstimationResult>
    ): List<CaseScoringResult> {
        val list = mutableListOf<CaseScoringResult>()
        for (er in estimationResults) {
            for ((paramName, paramValue) in er.parameters()) {
                val nc = CaseScoringResult()
                nc.caseID = dfTestCase.case.caseID
                nc.sampleSize = er.testData.size
                nc.sampleID = sampleID
                nc.estimatedDistribution = er.parameters?.rvType.toString()
                nc.resultType = "Parameter"
                nc.resultName = paramName
                nc.resultValue = paramValue
                list.add(nc)
            }
        }
        return list
    }

    companion object {

        fun classifyRank(rank: Double, actual: RVParametersTypeIfc, fitted: RVParametersTypeIfc): String {
            return if (actual != fitted) {
                if (rank != 1.0) {
                    "TN"
                } else {
                    "FP"
                }
            } else { // actual == fitted
                if (rank != 1.0) {
                    "FN"
                } else {
                    "TP"
                }
            }
        }

        fun classifyCase(rank: Double, actual: RVParametersTypeIfc, fitted: RVParametersTypeIfc): Classification {
            return if (actual != fitted) {
                if (rank != 1.0) {
                    Classification(0, 0)
                } else {
                    Classification(0, 1)
                }
            } else { // actual == fitted
                if (rank != 1.0) {
                    Classification(1, 0)
                } else {
                    Classification(1, 1)
                }
            }
        }

        /**
         *  This set holds predefined scoring models for evaluating
         *  the distribution goodness of fit.
         */
        val defaultScoringModels: Set<PDFScoringModel>  //TODO remove unneeded models
            get() = setOf(
                //ChiSquaredScoringModel(),
                KSScoringModel(),
                SquaredErrorScoringModel(),
                AndersonDarlingScoringModel(),
                CramerVonMisesScoringModel(),
                PPCorrelationScoringModel(),
                QQCorrelationScoringModel(),
                PPSSEScoringModel(),
                //QQSSEScoringModel(),
                //MallowsL2ScoringModel(),
                BayesianInfoCriterionScoringModel(),
                AkaikeInfoCriterionScoringModel()
            )

        /**
         *  Can be used to specify the estimators that are applied
         *  during the PDF distribution modeling process
         */
        val defaultEstimators: MutableSet<ParameterEstimatorIfc>
            get() = mutableSetOf(
                UniformParameterEstimator,
                TriangularParameterEstimator,
                NormalMLEParameterEstimator,
                GeneralizedBetaMOMParameterEstimator,
                ExponentialMLEParameterEstimator,
                LognormalMLEParameterEstimator,
                GammaMLEParameterEstimator(),
                WeibullMLEParameterEstimator(),
                PearsonType5MLEParameterEstimator()
            )
    }
}