package ksl.examples.general.utilities.fitting

import ksl.utilities.distributions.fitting.*
import ksl.utilities.distributions.fitting.estimators.*
import ksl.utilities.distributions.fitting.scoring.*
import ksl.utilities.io.KSL
import ksl.utilities.moda.MetricIfc
import ksl.utilities.moda.Score
import ksl.utilities.random.rvariable.GammaRV
import ksl.utilities.random.rvariable.RVParametersTypeIfc
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.statistic.Statistic
import java.nio.file.Path

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
                set.add(element)
            }
            return set
        }

    fun runCases() {
        for (case in cases) {
            for (sampleSize in case.sampleSizes) {
                runCase(case, sampleSize)
                if (messageOutput) {
                    println(byCaseOutput?.invoke(case, sampleSize))
                }
            }
        }
    }

    private fun runCase(dfTestCase: DFTestCase, sampleSize: Int) {
        // run each case for the specified number of samples
        saveCaseToDb(dfTestCase)
        for (i in 1..dfTestCase.case.numSamples) {
            val data = dfTestCase.rv.sample(sampleSize)
            val pdfModeler = PDFModeler(data, scoringModels = myScoringModels)
            // score evaluation process
            val pdfModelingResults = pdfModeler.estimateAndEvaluateScores(
                estimators = myEstimators,
                automaticShifting = dfTestCase.automaticShifting,
            )
            saveStatistics(dfTestCase.case.caseID, sampleSize, i, Statistic(data))
            saveFittingResults(dfTestCase, i, pdfModelingResults)
        }
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
    ) {
        val list = mutableListOf<CaseScoringResults>()
        // need to get estimation results, this will have the parameters
        val paramData = captureParameters(dfTestCase.case.caseID, sampleID, results.estimationResults)
        list.addAll(paramData)
        // need to capture the scoring evaluation
        val scoreData = captureScores(dfTestCase.case.caseID, sampleID, results.scoringResults)
        list.addAll(scoreData)
        // need to capture ranks
        val rankData = captureRanks(dfTestCase, sampleID, results)
        list.addAll(rankData)
        resultsDb.saveScoringResults(list)
    }

    private fun captureRanks(
        dfTestCase: DFTestCase,
        sampleID: Int,
        results: PDFModelingResults
    ): List<CaseScoringResults> {
        val list = mutableListOf<CaseScoringResults>()
        val metricRanks = metricRankByScoringResult(results)
        for ((sr, metricMap) in metricRanks) {
            for ((metric, rank) in metricMap) {
                val nc = CaseScoringResults()
                nc.caseID = dfTestCase.case.caseID
                nc.sampleSize = sr.estimationResult.originalData.size
                nc.sampleID = sampleID
                nc.estimatedDistribution = sr.rvType.toString()
                nc.resultType = "Metric Rank"
                nc.resultName = metric.name
                nc.resultValue = rank
                nc.classification = classifyRank(rank, dfTestCase.rvType(), sr.rvType)
                list.add(nc)
            }
        }
        return list
    }

    private fun classifyRank(rank: Double, actual: RVParametersTypeIfc, fitted: RVParametersTypeIfc): String {
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
    ): List<CaseScoringResults> {
        val list = mutableListOf<CaseScoringResults>()
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
    ): CaseScoringResults {
        val nc = CaseScoringResults()
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
    ): CaseScoringResults {
        val nc = CaseScoringResults()
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
    ): CaseScoringResults {
        val nc = CaseScoringResults()
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
    ): CaseScoringResults {
        val nc = CaseScoringResults()
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
    ): CaseScoringResults {
        val nc = CaseScoringResults()
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
        caseID: Int,
        sampleID: Int,
        estimationResults: List<EstimationResult>
    ): List<CaseScoringResults> {
        val list = mutableListOf<CaseScoringResults>()
        for (er in estimationResults) {
            for ((paramName, paramValue) in er.parameters()) {
                val nc = CaseScoringResults()
                nc.caseID = caseID
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
        /**
         *  This set holds predefined scoring models for evaluating
         *  the distribution goodness of fit.
         */
        val defaultScoringModels: Set<PDFScoringModel>  //TODO remove unneeded models
            get() = setOf(
//            ChiSquaredScoringModel(),
                KSScoringModel(),
//            SquaredErrorScoringModel(),
                AndersonDarlingScoringModel(),
//            CramerVonMisesScoringModel(),
                PPCorrelationScoringModel(),
//            QQCorrelationMetric(),
                PPSSEScoringModel(),
//            QQSSEScoringModel(),
//            MallowsL2ScoringModel()
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

fun main() {

    val sampleSizes = (40..4000 step 20).toSet()

    val testCases = listOf(
        DFTestCase(GammaRV(2.0, 2.0), setOf(40, 400), 10),
        DFTestCase(GammaRV(3.0, 2.0), setOf(40, 400), 10),
        DFTestCase(GammaRV(5.0, 2.0), setOf(40, 400), 10)
    )

    val dfExperiment = DFExperiment("Test_Cases", testCases)
    dfExperiment.messageOutput = true

    dfExperiment.runCases()

    println("Done")
}
