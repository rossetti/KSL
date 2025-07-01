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
package ksl.utilities.distributions.fitting

//import com.google.common.collect.HashBiMap
import ksl.utilities.collections.HashBiMap
import ksl.utilities.*
import ksl.utilities.collections.MutableBiMap
import ksl.utilities.distributions.*
import ksl.utilities.distributions.fitting.estimators.*
import ksl.utilities.distributions.fitting.scoring.*
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.plotting.ACFPlot
import ksl.utilities.io.plotting.BoxPlot
import ksl.utilities.io.plotting.ObservationsPlot
import ksl.utilities.io.toDataFrame
import ksl.utilities.io.toStatDataFrame
import ksl.utilities.moda.*
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.robj.DPopulation
import ksl.utilities.random.rvariable.*
import ksl.utilities.random.rvariable.parameters.GammaRVParameters
import ksl.utilities.random.rvariable.parameters.RVParameters
import ksl.utilities.statistic.*
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.api.remove
import org.jetbrains.kotlinx.dataframe.api.rename
import org.jetbrains.kotlinx.dataframe.io.DisplayConfiguration
import org.jetbrains.kotlinx.dataframe.io.toStandaloneHtml

/**
 *  Can be used to indicate if the recommended distribution should be
 *  based on the MODA scoring model or the first rank frequency across metrics.
 */
enum class EvaluationMethod {
    Scoring, Ranking
}

/**
 *  @param observations the data to analyze for fitting a probability distribution
 *  @param scoringModels the scoring models to use to evaluate the fitting process
 *  and recommend a distribution. By default, this is defaultScoringModels
 */
class PDFModeler(
    observations: DoubleArray,
    private val scoringModels: Set<PDFScoringModel> = defaultScoringModels,
) {
    private val myData: DoubleArray = observations.copyOf()

    val originalData: DoubleArray
        get() = myData.copyOf()

    private val myHistogram: Histogram by lazy {
        Histogram.create(myData, name = "PDF Modeler Default Histogram")
    }

    val histogram: HistogramIfc
        get() = myHistogram

    val statistics: StatisticIfc
        get() = myHistogram

    val hasZeroes: Boolean
        get() = myHistogram.zeroCount > 0

    val hasNegatives: Boolean
        get() = myHistogram.negativeCount > 0

    /**
     *  How close we consider a double is to 0.0 to consider it 0.0
     *  Default is 0.001
     */
    var defaultZeroTolerance : Double = PDFModeler.defaultZeroTolerance
        set(value) {
            require(value > 0.0) { "The default zero precision must be > 0.0" }
            field = value
        }

    /**
     *  Uses bootstrapping to estimate a confidence interval for the minimum
     */
    fun confidenceIntervalForMinimum(numBootstrapSamples: Int = 399, level: Double = 0.95): Interval {
        return confidenceIntervalForMinimum(myData, numBootstrapSamples, level)
    }

    /**
     *   Computes bootstrap confidence intervals for the estimated
     *   parameters of the distribution
     */
    fun bootStrapParameterEstimates(
        result: EstimationResult,
        numBootstrapSamples: Int = 399,
        level: Double = 0.95,
        streamNumber: Int = 0,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    ): List<BootstrapEstimate> {
        return bootStrapParameterEstimates(result.estimator, numBootstrapSamples, level,
            streamNumber, streamProvider, result.distribution)
    }

    /**
     *   Computes bootstrap confidence intervals for the estimated
     *   parameters of the distribution
     */
    fun bootStrapParameterEstimates(
        estimator: MVBSEstimatorIfc,
        numBootstrapSamples: Int = 399,
        level: Double = 0.95,
        streamNumber: Int = 0,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
        label: String? = null
    ): List<BootstrapEstimate> {
        val bss = BootstrapSampler(myData, estimator, streamNumber, streamProvider)
        val list = bss.bootStrapEstimates(numBootstrapSamples)
        for (e in list) {
            e.defaultCILevel = level
            e.label = label
        }
        return list
    }

    /**
     *  Estimates a possible shift parameter for the data.
     *  See PDFModeler.estimateLeftShiftParameter(). If any of the data are negative
     *  then there will be no shift. There must be at least 3 different positive values
     *  for a shift to be estimated; otherwise, it will be 0.0. Any estimated shift
     *  that is less that the defaultZeroTolerance will be set to 0.0.
     */
    val leftShift: Double
        get() = estimateLeftShiftParameter(myData, defaultZeroTolerance)

    /**
     *  Estimates the parameters for all estimators represented by
     *  the set of [estimators]. The parameter [automaticShifting] controls
     *  whether the data will be automatically shifted.
     *
     *  If the automatic shift parameter is true (the default), then a
     *  confidence interval for the minimum of the data is estimated from the data.
     *  If the upper limit of the estimated confidence interval is greater than the value specified by the default
     *  zero tolerance property, then the data is shifted to the left and used in the estimation process.
     *  The estimated shift will be recorded in the result.  Automated shift estimation
     *  will occur only if the automatic shifting parameter is true, and
     *  the estimator requires that its range be checked and
     *  if the data actually requires a shift.  If the automatic shifting
     *  parameter is false, then no shifting will occur.  In this case it is up to
     *  the user to ensure that the supplied data is representative of the set
     *  of estimators to be estimated.
     *
     *  The returned list will contain the results for
     *  each estimator.  Keep in mind that some estimators may fail the estimation
     *  process, which will be noted in the success property of the estimation results.
     */
    fun estimateParameters(
        estimators: Set<ParameterEstimatorIfc>,
        automaticShifting: Boolean = true
    ): List<EstimationResult> {
        // estimate a confidence interval on the minimum value
        var shiftedData: ShiftedData? = null
        if (automaticShifting) {
            val minCI = confidenceIntervalForMinimum()
            if (defaultZeroTolerance < minCI.lowerLimit) {
                shiftedData = leftShiftData(myData)
            }
        }
        val shiftedStats = shiftedData?.shiftedData?.statistics()
        val estimatedParameters = mutableListOf<EstimationResult>()
        for (estimator in estimators) {
            val result = if (estimator.checkRange && (shiftedData != null)) {
                val r = estimator.estimateParameters(shiftedData.shiftedData, shiftedStats!!)
                r.shiftedData = shiftedData
                r
            } else {
                estimator.estimateParameters(myData, statistics)
            }
            estimatedParameters.add(result)
        }
        return estimatedParameters
    }

    /**
     *   A convenience method for invoking parameter estimation for a single
     *   instance of ParameterEstimatorIfc. The parameter [automaticShifting] controls
     *   whether the data will be automatically shifted.
     */
    fun estimateParameters(
        estimator: ParameterEstimatorIfc,
        automaticShifting: Boolean = true
    ): EstimationResult {
        return estimateParameters(setOf(estimator), automaticShifting).first()
    }

    /**
     *  Estimation results in the list of [results] are scored by each scoring model in
     *  the supplied set scoring models.  Any estimation results within the supplied list
     *  that were not successfully estimated or had no parameters estimated will
     *  not be scored.  The returned list contains instances holding the scoring
     *  results for each successfully estimated distribution.
     */
    fun scoringResults(
        results: List<EstimationResult>,
    ): List<ScoringResult> {
        val list = mutableListOf<ScoringResult>()
        // copied because models and their metrics may be mutated during the scoring process
        val scoringModelSet: Set<PDFScoringModel> = scoringModels.map { it.newInstance() }.toSet()
        for (result in results) {
            if (!result.success || (result.parameters == null)) {
                continue
            }
            val distribution = createDistribution(result.parameters) ?: continue
            val name = if (result.shiftedData != null) {
                "${result.shiftedData!!.shift} + $distribution"
            } else {
                distribution.toString()
            }
            val scores = mutableListOf<Score>()
            //metric rescaling may occur for copied scoring model
            for (model in scoringModelSet) {
                val score = model.score(result)
                scores.add(score)
            }
            val sr = ScoringResult(name, distribution, result, result.parameters.rvType, scores)
            list.add(sr)
        }
        return list
    }

    /**
     *  Evaluates the supplied scoring results using the supplied
     *  evaluation model.  A default additive MODA model is supplied
     *  that uses linear value functions for each metric.
     */
    fun evaluateScoringResults(
        scoringResults: List<ScoringResult>,
        rankingMethod: Statistic.Companion.Ranking = defaultRankingMethod,
        model: AdditiveMODAModel = createDefaultPDFEvaluationModel(scoringResults, rankingMethod)
    ): AdditiveMODAModel {
        if (scoringResults.isEmpty()) {
            return model
        }
        val metrics = scoringResults[0].metrics
        val mm = model.metrics
        require(metricsMatch(metrics, mm)) { "The metrics in the model do not match the metrics in the scores" }
        val alternatives = mutableMapOf<String, List<Score>>()
        for (sr in scoringResults) {
            alternatives[sr.name] = sr.scores
        }
        model.defineAlternatives(alternatives)//this can cause metric domain rescaling
        //TODO this is where I can capture the ranking recommendation into the scoring result
        //TODO need to specify the ranking method as a parameter of the function
        val firstCounts = model.alternativeFirstRankCounts(false, rankingMethod).toMap()
        val avgRankings = model.alternativeAverageRanking(false, rankingMethod).toMap()
        for (sr in scoringResults) {
            sr.values = model.valuesByAlternative(sr.name)
            sr.weightedValue = model.multiObjectiveValue(sr.name)
            sr.weights = model.weights
            sr.firstRankCount = firstCounts[sr.name]!!
            sr.averageRanking = avgRankings[sr.name]!!
        }
        return model
    }

    /**
     *  Checks if the two lists of metrics are the same.
     */
    private fun metricsMatch(m1: List<MetricIfc>, m2: List<MetricIfc>): Boolean {
        require(m1.size == m2.size) { "The number of metrics in the model is not the same as the scoring metrics" }
        for ((i, _) in m1.withIndex()) {
            if (m1[i] != m2[i]) return false
        }
        return true
    }

    /**
     *   This function estimates the parameters based on the supplied
     *   [estimators] and scores the estimators based on the supplied
     *   scoring models [scoringModels]. By default, a shift parameter
     *   for the distributions is estimated. The results
     *   are bundles up into a class that holds the estimation results,
     *   the scoring results, and the model used for evaluating
     *   the model goodness of fit.
     */
    fun estimateAndEvaluateScores(
        estimators: Set<ParameterEstimatorIfc> = allEstimators,
        automaticShifting: Boolean = true,
    ): PDFModelingResults {
        val estimationResults = estimateParameters(estimators, automaticShifting)
        return evaluateScores(estimationResults)
    }

    /**
     *   This function applies the supplied scoring models to the estimation results.
     */
    fun evaluateScores(
        estimationResults: List<EstimationResult>,
    ): PDFModelingResults {
        val scoringResults = scoringResults(estimationResults)
        //this can cause metric domain rescaling
        val evaluationModel = evaluateScoringResults(scoringResults)
        val results = PDFModelingResults(estimationResults, scoringResults, evaluationModel)
        return results
    }

    /**
     *  Presents a statistical summary of the data in html format.
     *  This includes that summary statistics, box plot statistics,
     *  histogram statistics, and an analysis of the shift parameter.
     */
    fun htmlStatisticalSummary(): String {
        // produce html results
        // basic statistics and box plot summary data
        val statDf = histogram.toStatDataFrame()
        val statConfig = DisplayConfiguration.DEFAULT
        statConfig.rowsLimit = statDf.rowsCount()
        // box plot summary
        val bp = BoxPlotSummary(myData)
        val boxPlotDf = bp.toDataFrame()
        val boxConfig = DisplayConfiguration.DEFAULT
        boxConfig.rowsLimit = boxPlotDf.rowsCount()
        // histogram statistics
//        var histDf = histogram.toDataFrame()
//        histDf = histDf.remove("id")
//        histDf = histDf.remove("name")
//        histDf = histDf.remove("binLabel")
        val config = DisplayConfiguration.DEFAULT
        config.rowsLimit = histogram.numberBins + 1
        // estimate left shift parameter
        val leftShift = estimateLeftShiftParameter(myData)
        val minCI = confidenceIntervalForMinimum(myData)
        val sb = StringBuilder().apply {
            appendLine("<h1>")
            appendLine("Statistical Summary")
            appendLine("</h1>")
            appendLine("<div>")
//            appendLine(statDf.toStandaloneHTML(configuration = statConfig))
            appendLine("<pre>")
            val s = histogram.statisticsAsMap
            for ((key, value) in s) {
                appendLine("$key = $value")
            }
//            appendLine(histogram.statisticsAsMap)
            appendLine("</pre>")
            appendLine("</div>")
            appendLine("<h1>")
            appendLine("Box Plot Summary")
            appendLine("</h1>")
            appendLine("<div>")
//            appendLine(boxPlotDf.toStandaloneHTML(configuration = boxConfig))
            appendLine("<pre>")
            val bs = bp.asMap()
            for ((key, value) in bs) {
                appendLine("$key = $value")
            }
            appendLine("Outlier Summary:")
            appendLine(bp.outlierResults())
            appendLine("</pre>")
            appendLine("</div>")
            appendLine("<h1>")
            appendLine("Histogram Summary")
            appendLine("</h1>")
            appendLine("<div>")
            appendLine("<pre>")
            appendLine(histogram.asString())
            appendLine("</pre>")
//            appendLine(histDf.toStandaloneHTML(configuration = config))
            appendLine("</div>")
            appendLine("<div>")
            appendLine("<h1>")
            appendLine("Shift Parameter Analysis")
            appendLine("</h1>")
            appendLine("<pre>")
            appendLine("Estimated Left Shift Parameter: $leftShift")
            appendLine("Confidence Interval for Minimum: $minCI")
            appendLine("</pre>")
            appendLine("</div>")
            appendLine("<p>")
            appendLine("</p>")
        }
        return sb.toString()
    }

    /**
     *  The histogram portion of the results as html.
     *  The optional argument [plotFileName] can be used to cause
     *  a PNG file to be saved to the plot directory.
     */
    fun htmlHistogram(plotFileName: String? = null): String {
        val hPlot = histogram.histogramPlot()
        if (plotFileName != null) {
            hPlot.saveToFile("${plotFileName}_Hist_Plot")
        }
        val sb = StringBuilder().apply {
            appendLine("<h1>")
            appendLine("Histogram Results")
            appendLine("</h1>")
            appendLine("<div>")
            appendLine(hPlot.toHTML())
            appendLine("</div>")
        }
        return sb.toString()
    }

    /**
     *  Presents the histograms, box plot, observation plot,
     *  and auto-correlation plot for the data.
     */
    fun htmlVisualizationSummary(): String {
        // produce html results
        // KSL histogram
        val hPlot = histogram.histogramPlot()
        // histogram with density overlay
 //       val hdPlot = HistogramDensityPlot(data)
        // box plot
        val bp = BoxPlot(myData)
        // observation plot
        val op = ObservationsPlot(myData)
        // acf plot
        val acf = ACFPlot(myData)
        val sb = StringBuilder().apply {
            appendLine("<h1>")
            appendLine("Visualization Results")
            appendLine("</h1>")
            appendLine("<h2>")
            appendLine("Histogram")
            appendLine("</h2>")
            appendLine("<div>")
            appendLine(hPlot.toHTML())
            appendLine("</div>")
//            appendLine("<div>")
//            appendLine(hdPlot.toHTML())
//            appendLine("</div>")
            appendLine("<h2>")
            appendLine("Box Plot")
            appendLine("</h2>")
            appendLine("<div>")
            appendLine(bp.toHTML())
            appendLine("</div>")
            appendLine("<h2>")
            appendLine("Observation Plot")
            appendLine("</h2>")
            appendLine("<div>")
            appendLine(op.toHTML())
            appendLine("</div>")
            appendLine("<h2>")
            appendLine("Autocorrelation Plot")
            appendLine("</h2>")
            appendLine("<div>")
            appendLine(acf.toHTML())
            appendLine("</div>")
        }
        return sb.toString()
    }

    /**
     *  The observations plot portion of the results as html.
     *  The optional argument [plotFileName] can be used to cause
     *  a PNG file to be saved to the plot directory.
     */
    fun htmlObservationPlot(plotFileName: String? = null): String {
        val op = ObservationsPlot(myData)
        if (plotFileName != null) {
            op.saveToFile("${plotFileName}_Obs_Plot")
        }
        val sb = StringBuilder().apply {
            appendLine("<h1>")
            appendLine("Observation Plot Results")
            appendLine("</h1>")
            appendLine("<div>")
            appendLine(op.toHTML())
            appendLine("</div>")
        }
        return sb.toString()
    }

    /**
     *  The ACF plot portion of the results as html.
     *  The optional argument [plotFileName] can be used to cause
     *  a PNG file to be saved to the plot directory.
     */
    fun htmlACFPlot(plotFileName: String? = null): String {
        val acf = ACFPlot(myData)
        if (plotFileName != null) {
            acf.saveToFile("${plotFileName}_ACF_Plot")
        }
        val sb = StringBuilder().apply {
            appendLine("<h1>")
            appendLine("Autocorrelation Plot Results")
            appendLine("</h1>")
            appendLine("<div>")
            appendLine(acf.toHTML())
            appendLine("</div>")
        }
        return sb.toString()
    }

    /**
     *  Produces a html representation of the scoring and metric evaluation
     *  results including the recommended distribution.
     */
    fun htmlScoringSummary(
        pdfModelingResults: PDFModelingResults,
        rankingMethod: Statistic.Companion.Ranking = defaultRankingMethod
    ): String {
        // produce html results
        // scoring data frame
        val scores = pdfModelingResults.scoresAsDataFrame()
        // values data frame
        val values = pdfModelingResults.metricsAsDataFrame()
        val ranks = pdfModelingResults.ranksAsDataFrame(rankingMethod)
        val configuration: DisplayConfiguration = DisplayConfiguration.DEFAULT
        configuration.cellContentLimit = 120
        configuration.rowsLimit = scores.rowsCount()
        val sb = StringBuilder().apply {
            appendLine("<div>")
            appendLine("<h1>")
            appendLine("PDF Modeling Results")
            appendLine("</h1>")
            appendLine("<h2>")
            appendLine("Scores:")
            appendLine("</h2>")
            appendLine(scores.toStandaloneHtml(configuration))
            appendLine("</div>")
            appendLine("<div>")
            appendLine("<h2>")
            appendLine("Metric Evaluations:")
            appendLine("</h2>")
            appendLine(values.toStandaloneHtml(configuration))
            appendLine("</div>")
            appendLine("<div>")
            appendLine("<h2>")
            appendLine("Rank Evaluations:")
            appendLine("</h2>")
            appendLine(ranks.toStandaloneHtml(configuration))
            appendLine("</div>")
        }
        return sb.toString()
    }

    /**
     *  Shows the goodness of fit summaries for every distribution
     *  that was fit within the modeling results within the browser.
     */
    fun showAllGoodnessOfFitSummariesInBrowser(
        pdfModelingResults: PDFModelingResults,
        resultsFileName: String = "PDF_Modeling_Goodness_Of_Fit_Summaries"
    ){
        KSLFileUtil.openInBrowser(
            fileName = resultsFileName,
            htmlGoodnessOfFitSummaries(pdfModelingResults)
        )
    }

    /**
     *  Constructs an HTML representation of the goodness of fit
     *  summaries for the distributions that were fit by the modeler.
     */
    fun htmlGoodnessOfFitSummaries(
        pdfModelingResults: PDFModelingResults
    ) : String {
        val sb = StringBuilder()
        sb.appendLine(htmlScoringSummary(pdfModelingResults, defaultRankingMethod))
        sb.appendLine("<div>")
        sb.appendLine("<h1>")
        sb.appendLine("Goodness of Fit Results for All Fitted Distributions")
        sb.appendLine("</h1>")
        sb.appendLine("</div>")
        for(result in pdfModelingResults.resultsSortedByScoring){
            sb.append(htmlGoodnessOfFitSummary(result))
        }
        return sb.toString()
    }

    /**
     *  Produces a html representation of the goodness of fit results which
     *  include the distribution fit quad plot and the chi-squared goodness
     *  of fit statistics for a particular scoring result.
     */
    fun htmlGoodnessOfFitSummary(
        result: ScoringResult,
        plotFileName: String? = null
    ): String {
        val distPlot = result.distributionFitPlot()
        if (plotFileName != null) {
            distPlot.saveToFile("${plotFileName}_PDF_Plot")
        }
        // goodness of fit results
        val gof = ContinuousCDFGoodnessOfFit(
            result.estimationResult.testData,
            result.distribution,
            numEstimatedParameters = result.numberOfParameters
        )
        val sb = StringBuilder().apply {
            appendLine("<div>")
            appendLine("<h1>")
            appendLine("PDF Goodness of Fit Results")
            appendLine("</h1>")
            appendLine("<h2>")
            appendLine("Distribution:")
            appendLine("</h2>")
            appendLine("<p>")
            appendLine(result.name)
            appendLine("</p>")
            appendLine("</div>")
            val bs = bootStrapParameterEstimates(result.estimationResult)
            appendLine("<div>")
            appendLine("<h2>")
            appendLine("Bootstrap Results for Parameter Estimation:")
            appendLine("</h2>")
            appendLine("<pre>")
            for (bse in bs) {
                appendLine(bse.toString())
            }
            appendLine("</pre>")
            appendLine("</div>")
            appendLine("<div>")
            appendLine("<h2>")
            appendLine("Distribution Plot Results")
            appendLine("</h2>")
            appendLine(distPlot.toHTML())
            appendLine("</div>")
            appendLine("<div>")
            appendLine("<pre>")
            appendLine(gof.toString())
            appendLine("</pre>")
            appendLine("</div>")
        }
        return sb.toString()
    }

    /**
     *  Produces a html representation of the goodness of fit results which
     *  include the distribution fit quad plot and the chi-squared goodness
     *  of fit statistics for the recommended distribution.
     */
    fun htmlGoodnessOfFitSummary(
        pdfModelingResults: PDFModelingResults,
        evaluationMethod: EvaluationMethod = EvaluationMethod.Scoring,
        plotFileName: String? = null
    ): String {
        // produce html results
        // distribution quad evaluation plot
        val result = if (evaluationMethod == EvaluationMethod.Scoring) {
            pdfModelingResults.topResultByScore
        } else {
            pdfModelingResults.topResultByRanking
        }
        return htmlGoodnessOfFitSummary(result, plotFileName)
    }

    /**
     *  This function will apply the estimators to the data and report all the results
     *  in HTML format.
     *  @param estimators the estimators to apply, by default (allEstimators)
     *  @param automaticShifting true by default, if true applies automatic shifting to the estimation process
     *  @param pdfModelingResults the results of applying the estimators and evaluating the scores
     *  @param rankingMethod the ranking method to use when calculating the ranks
     *  @param statResultsFileName a file name for statistical results
     *  Default = "PDF_Modeling_Statistical_Summary"
     *  @param visualizationResultsFileName a file name for visualization results
     *  Default = "PDF_Modeling_Visualization_Summary"
     *  @param scoringResultsFileName a file name for scoring results
     *  Default = "PDF_Modeling_Scoring_Summary"
     *  @param goodnessOfFitResultsFileName a file name for goodness of fit results.
     *  Default = "PDF_Modeling_GoodnessOfFit_Summary"
     */
    fun showAllResultsInBrowser(
        estimators: Set<ParameterEstimatorIfc> = allEstimators,
        automaticShifting: Boolean = true,
        pdfModelingResults: PDFModelingResults = estimateAndEvaluateScores(estimators, automaticShifting),
        rankingMethod: Statistic.Companion.Ranking = defaultRankingMethod,
        evaluationMethod: EvaluationMethod = EvaluationMethod.Scoring,
        statResultsFileName: String = "PDF_Modeling_Statistical_Summary",
        visualizationResultsFileName: String = "PDF_Modeling_Visualization_Summary",
        scoringResultsFileName: String = "PDF_Modeling_Scoring_Summary",
        goodnessOfFitResultsFileName: String = "PDF_Modeling_GoodnessOfFit_Summary",
    ) : PDFModelingResults {
        KSLFileUtil.openInBrowser(
            fileName = statResultsFileName,
            htmlStatisticalSummary()
        )
        KSLFileUtil.openInBrowser(
            fileName = visualizationResultsFileName,
            htmlVisualizationSummary()
        )
        KSLFileUtil.openInBrowser(
            fileName = scoringResultsFileName,
            htmlScoringSummary(pdfModelingResults, rankingMethod)
        )
        KSLFileUtil.openInBrowser(
            fileName = goodnessOfFitResultsFileName,
            htmlGoodnessOfFitSummary(pdfModelingResults, evaluationMethod)
        )
        return pdfModelingResults
    }

    companion object {

        enum class DefaultScalingFunction {
            Linear, Logistic
        }

        var defaultScalingFunction: DefaultScalingFunction = DefaultScalingFunction.Linear

        /**
         *  For rank based evaluation, this specifies the default parameter value
         *  for those methods the perform rank based evaluation calculations.
         */
        var defaultRankingMethod: Statistic.Companion.Ranking = Statistic.Companion.Ranking.Ordinal

        /**
         *  This set contains all the known estimators for estimating continuous
         *  distributions. This is the union of nonRestrictedEstimators and positiveRestrictedEstimators
         */
        val allEstimators: Set<ParameterEstimatorIfc>
            get() = nonRestrictedEstimators union positiveRestrictedEstimators

        /**
         *  This set holds estimators that can fit distributions for which
         *  the domain is not restricted.
         */
        val nonRestrictedEstimators: Set<ParameterEstimatorIfc>
            get() = setOf(
                UniformParameterEstimator,
                TriangularParameterEstimator,
                NormalMLEParameterEstimator,
                GeneralizedBetaMOMParameterEstimator
            )

        /**
         *  This set holds the recommended estimators for estimating the
         *  parameters of distributions on the positive domain x in (0, infinity)
         */
        val positiveRestrictedEstimators: Set<ParameterEstimatorIfc>
            get() = setOf(
                ExponentialMLEParameterEstimator,
                LognormalMLEParameterEstimator,
                GammaMLEParameterEstimator(),
                WeibullMLEParameterEstimator(),
                PearsonType5MLEParameterEstimator()
            )

        /**
         *  This set holds predefined scoring models for evaluating
         *  the distribution goodness of fit.
         */
        val defaultScoringModels: Set<PDFScoringModel>
            get() = setOf(
                BayesianInfoCriterionScoringModel(),
                AndersonDarlingScoringModel(),
                CramerVonMisesScoringModel(),
                QQCorrelationScoringModel()
            )

        /**
         *  Uses bootstrapping to estimate a confidence interval for the minimum
         */
        fun confidenceIntervalForMinimum(
            data: DoubleArray,
            numBootstrapSamples: Int = 399,
            level: Double = 0.95
        ): Interval {
            val bootStrap: Bootstrap = Bootstrap(data, BSEstimatorIfc.Minimum())
            bootStrap.generateSamples(numBootstrapSamples)
            return bootStrap.percentileBootstrapCI(level)
        }

        /**
         *  Uses bootstrapping to estimate a confidence interval for the maximum
         */
        fun confidenceIntervalForMaximum(
            data: DoubleArray,
            numBootstrapSamples: Int = 399,
            level: Double = 0.95
        ): Interval {
            val bootStrap: Bootstrap = Bootstrap(data, BSEstimatorIfc.Maximum())
            bootStrap.generateSamples(numBootstrapSamples)
            return bootStrap.percentileBootstrapCI(level)
        }

        /**
         *  Creates an additive evaluation model based on the metrics within the
         *  scoring results that has linear value functions for the metrics.
         *  The list of scoring results must not be empty.
         *  @param scoringResults the list os scoring results, must not be empty
         *  @param rankingMethod the desired ranking method. The default is specified by
         *  the companion property defaultRankingMethod
         *  @param scalingFunction the type of scaling function to use. By default, this is
         *  specified by the companion property, defaultScalingFunction
         */
        fun createDefaultPDFEvaluationModel(
            scoringResults: List<ScoringResult>,
            rankingMethod: Statistic.Companion.Ranking = defaultRankingMethod,
            scalingFunction: DefaultScalingFunction = defaultScalingFunction,
        ): AdditiveMODAModel {
            require(scoringResults.isNotEmpty()) { "The list of scoring results was empty" }
            val metricValueFunctionMap = if (scalingFunction == DefaultScalingFunction.Logistic) {
                createLogisticFunctionMetricEvaluationFunctionMap(scoringResults)
            } else {
                val metrics = scoringResults[0].metrics
                MODAModel.assignLinearValueFunctions(metrics)
            }
            val model = AdditiveMODAModel(metricValueFunctionMap, name = "Default PDF MODA")
            model.defaultRankingMethod = rankingMethod
            return model
        }

        var defaultLogisticFunctionFactor : Double = 0.25
            set(value) {
                require((0.0 < value) && (value < 1.0)) { "The factor must be within (0,1)" }
                field = value
            }

        /**
         *  Creates a map for an AdditiveMODAModel that can specify the metrics using
         *  LogisticFunction value functions
         *  The list of scoring results must not be empty.
         *  @param scoringResults the list os scoring results, must not be empty
         */
        fun createLogisticFunctionMetricEvaluationFunctionMap(
            scoringResults: List<ScoringResult>
        ): Map<MetricIfc, ValueFunctionIfc> {
            require(scoringResults.isNotEmpty()) { "The list of scoring results was empty" }
            val metrics = scoringResults[0].metrics
            // We need the metric values for each alternative (distribution)
            // e.g. all the BIC values (the BIC value for each distribution)
            // make the map to hold the raw score values for each metric
            val metricData = mutableMapOf<MetricIfc, MutableList<Double>>()
            for(m in metrics){
                metricData[m] = mutableListOf()
            }
            // Now extract the data. List<Scores> within ScoringResult has the data.
            // Each score has the metric and its associated raw score value
            for(sr in scoringResults){
                // scoring results go across the alternatives
                for(score in sr.scores){
                    // get the list for the metric and add the associated score
                    metricData[score.metric]!!.add(score.value)
                }
            }
            // We need to build the logistic functions based on the metric score values
            // make the map and assign the value function for each metric then return
            val metricValueFunctionMap = mutableMapOf<MetricIfc, ValueFunctionIfc>()
            for((metric, data) in metricData){
                val f = LogisticFunction.create(data.toDoubleArray(), defaultLogisticFunctionFactor)
                metricValueFunctionMap[metric] = f
            }
            return metricValueFunctionMap
        }

        /**
         *  How close we consider a double is to 0.0 to consider it 0.0
         *  Default is 0.95
         */
        var defaultConfidenceLevel : Double = 0.95
            set(level) {
                require(!(level <= 0.0 || level >= 1.0)) { "Confidence Level must be (0,1)" }
                field = level
            }

        /**
         *  How close we consider a double is to 0.0 to consider it 0.0
         *  Default is 0.001
         */
        var defaultZeroTolerance : Double = 0.001
            set(value) {
                require(value > 0.0) { "The default zero precision must be > 0.0" }
                field = value
            }

        /**
         *  Uses the method described on page 360 of Law (2007)
         *  Simulation Modeling and Analysis, ISBN 0073294411, 9780073294414
         *  There must be at least three observations within the [data] and there
         *  must be at least three different values.  That is, all the values must not be the same.
         *  The observations should all be greater than or equal to 0.0. That is,
         *  no negative values are allowed within the [data]. If these conditions do not hold,
         *  then 0.0 is returned for the shift. That is, no shift.
         *
         *  The value [tolerance] is used to consider whether the computed shift value is close enough
         *  to zero to consider the shift 0.0.  The default is 0.001.  That is, a value
         *  of the estimated shift that is less than the tolerance are considered 0.0.
         *
         *  This approach estimates a shift parameter that is intended to
         *  shift the distribution to the left.  If X(i) is the original datum,
         *  then the shifted data is intended to be Y(i) = X(i) - shift.  Thus, the distribution
         *  is shifted to the left.  The estimated shift should be a positive quantity.
         */
        fun estimateLeftShiftParameter(data: DoubleArray, tolerance: Double = defaultZeroTolerance): Double {
            if (data.size < 3) {
                return 0.0
            }
            val min = data.min()
            if (min < 0.0) {
                return 0.0
            }
            val max = data.max()
            if (max < 0.0) {
                return 0.0
            }
            if (min == max) {
                return 0.0
            }
            val nextSmallest = findNextLargest(data, min)
            if (nextSmallest == max) {
                return 0.0
            }
            return estimateLeftShift(min, nextSmallest, max, tolerance)
        }

        /**
         *  If [min] is the minimum of the [data], then this computes the observation
         *  that is strictly larger than the observed minimum.  This is the minimum
         *  of the observations if all observations of [min] are removed from
         *  the data. There must be at least 2 observations and the observations
         *  cannot all be equal.  There must be at least one observation that is
         *  not equal to [min].
         */
        fun findNextLargest(data: DoubleArray, min: Double): Double {
            require(data.size >= 2) { "There must be at least two observations." }
            require(!data.isAllEqual()) { "The observations cannot all be equal." }
            val remaining = data.removeValue(min)
            require(remaining.isNotEmpty()) { "All observations were equal to the minimum." }
            return remaining.min()
        }

        /**
         *  Estimates the range based on uniform distribution theory.
         *   Uses the minimum unbiased estimators based on the order statistics.
         *   See: 1. Castillo E, Hadi AS. A method for estimating parameters and quantiles of
         *   distributions of continuous random variables. Computational Statistics & Data Analysis.
         *   1995 Oct;20(4):421–39.
         *
         *   This approach guarantees that the estimated lower limit will be less than the observed
         *   minimum and that the estimate upper limit will be greater than the observed maximum.
         */
        fun rangeEstimate(min: Double, max: Double, n: Int): Interval {
            require(n >= 2) { "There must be at least two observations." }
            require(min < max) { "The minimum must be strictly less than the maximum." }
            val range = max - min
            val a = min - (range / (n - 1.0))
            val b = max + (range / (n - 1.0))
            return Interval(a, b)
        }

        private fun findNextSmallestV2(data: DoubleArray, min: Double): Double {
            val sorted = data.orderStatistics()
            var xk = sorted[1]
            for (k in 1 until sorted.size - 1) {
                if (sorted[k] > min) {
                    xk = sorted[k]
                    break
                }
            }
            return xk
        }

        /**
         *  Uses the method described on page 360 of Law (2007)
         *  Simulation Modeling and Analysis, ISBN 0073294411, 9780073294414
         *  The [min] must be strictly less than the [max].  The [nextSmallest] is x(k)
         *  where x(k) is the kth order statistic and k is the value in {2, 3, ..., n-1}
         *  such that x(k) is strictly greater than x(1).
         *
         *  The value [tolerance] is used to consider whether the computed shift value is close enough
         *  to zero to consider the shift 0.0.  The default is 0.001.  That is, a value
         *  of the estimated shift that is less than the tolerance is considered 0.0.
         *
         *  This approach estimates a shift parameter that is intended to
         *  shift the distribution to the left.  If X(i) is the original datum,
         *  then the shifted data is intended to be Y(i) = X(i) - shift.  Thus, the distribution
         *  is shifted to the left.  The estimated shift should be a positive quantity.
         */
        fun estimateLeftShift(
            min: Double,
            nextSmallest: Double,
            max: Double,
            tolerance: Double = defaultZeroTolerance
        ): Double {
            require(min > 0.0) { "The minimum must be > 0.0" }
            require(min < max) { "The minimum must be strictly less than the maximum." }
            require(nextSmallest > min) { "The next smallest value must not be equal to the minimum." }
            require(nextSmallest < max) { "The next smallest value must be strictly less than the maximum." }
            val top = min * max - nextSmallest * nextSmallest
            if (top == 0.0) {
                return 0.0
            }
            val bottom = min + max - 2.0 * nextSmallest
            val shift = top / bottom
            return if (shift <= tolerance) 0.0 else shift
        }

        /**
         *  Estimates the shift parameter and then shifts the
         *  data by the estimated quantity. Also returns the computed
         *  shift. Use destructuring if you want:
         *
         *  val (shift, shiftedData) = shiftData(data)
         */
        fun leftShiftData(data: DoubleArray, tolerance: Double = defaultZeroTolerance): ShiftedData {
            val d = data.copyOf()
            val shift = estimateLeftShiftParameter(d, tolerance)
            return ShiftedData(shift, KSLArrays.subtractConstant(d, shift))
        }

        /**
         *  Computes breakpoints for the distribution that ensures (approximately) that
         *  the expected number of observations within the intervals defined by the breakpoints
         *  will be equal. That is, the probability associated with each interval is
         *  equal. In addition, the expected number of observations will be approximately
         *  greater than or equal to 5.  There will be at least two breakpoints and thus at least
         *  3 intervals defined by the breakpoints.
         *
         *  If the sample size [sampleSize] is less than 15, then the approximate
         *  expected number of observations within the intervals may not be greater than or equal to 5.
         *  Note that the returned break points do not consider the range of the CDF
         *  and may require end points to be added to the beginning or end of the array
         *  to adjust for the range of the CDF.
         *
         *  The returned break points are based on the natural domain of the implied
         *  CDF and do not account for any shift that may be needed during the modeling
         *  process.
         */
        fun equalizedCDFBreakPoints(sampleSize: Int, inverse: InverseCDFIfc): DoubleArray {
            if (sampleSize < 15) {
                // there should be at least two breakpoints, dividing U(0,1) equally
                return inverse.invCDF(doubleArrayOf(1.0 / 3.0, 2.0 / 3.0))
            }
            val p = U01Test.recommendedU01BreakPoints(sampleSize, defaultConfidenceLevel)
            val bp = inverse.invCDF(p)
            bp.sort() //must be increasing
            return bp.toSet().toDoubleArray() // must be unique
        }

        /**
         * Returns the probability of being in the bin,
         * F(upper limit) - F(lower limit)
         */
        fun binProbability(bin: HistogramBin, df: ContinuousDistributionIfc): Double {
            return df.cdf(bin.lowerLimit, bin.upperLimit)
        }

        /**
         * Returns the probability of being in each bin,
         * F(upper limit) - F(lower limit) within the bins
         * with p[0] for bins[0] etc.
         */
        fun binProbabilities(bins: List<HistogramBin>, df: ContinuousDistributionIfc): DoubleArray {
            return DoubleArray(bins.size) { binProbability(bins[it], df) }
        }

        /**
         *  The expected number of observations within the bin given
         *  a particular [cdf]
         */
        fun expectedCount(histogram: Histogram, binNum: Int, cdf: ContinuousDistributionIfc): Double {
            return histogram.count * binProbability(histogram.bin(binNum), cdf)
        }

        /**
         *  The expected number of observations in each bin given
         *  a particular [cdf].
         */
        fun expectedCounts(histogram: Histogram, cdf: ContinuousDistributionIfc): DoubleArray {
            val p = binProbabilities(histogram.bins, cdf)
            return p.multiplyConstant(histogram.count)
        }

        /**
         *  Uses the method of moments to fit a gamma distribution to the supplied data.
         *  The supplied statistics must be the statistics for the supplied data for
         *  this method to return results consistent with the supplied data.
         */
        internal fun gammaMOMEstimator(
            data: DoubleArray,
            statistics: StatisticIfc,
            estimator: MVBSEstimatorIfc
        ): EstimationResult {
            if (data.size < 2) {
                return EstimationResult(
                    originalData = data,
                    statistics = statistics,
                    message = "There must be at least two observations",
                    success = false,
                    estimator = estimator
                )
            }
            if (data.countLessThan(0.0) > 0) {
                return EstimationResult(
                    originalData = data,
                    statistics = statistics,
                    message = "Cannot fit gamma distribution when some observations are less than 0.0",
                    success = false,
                    estimator = estimator
                )
            }
            if (statistics.average <= 0.0) {
                return EstimationResult(
                    originalData = data,
                    statistics = statistics,
                    message = "The sample average of the data was <= 0.0",
                    success = false,
                    estimator = estimator
                )
            }
            if (statistics.variance <= 0.0) {
                return EstimationResult(
                    originalData = data,
                    statistics = statistics,
                    message = "The sample variance of the data was <= 0.0",
                    success = false,
                    estimator = estimator
                )
            }
            val params = Gamma.parametersFromMeanAndVariance(statistics.average, statistics.variance)
            val parameters = GammaRVParameters()
            parameters.changeDoubleParameter("shape", params[0])
            parameters.changeDoubleParameter("scale", params[1])
            return EstimationResult(
                originalData = data,
                statistics = statistics,
                parameters = parameters,
                message = "The gamma parameters were estimated successfully using a MOM technique",
                success = true,
                estimator = estimator
            )
        }

        /**
         *  Constructs an instance of the appropriate continuous probability distribution
         *  for the provided random variable [parameters].  If no probability distribution
         *  is defined for the supplied type of random variable, then null is returned.
         */
        fun createDistribution(parameters: RVParameters): ContinuousDistributionIfc? {

            return when (parameters.rvType) {
                RVType.Beta -> {
                    val alpha = parameters.doubleParameter("alpha")
                    val beta = parameters.doubleParameter("beta")
                    return Beta(alpha, beta)
                }

                RVType.Exponential -> {
                    val mean = parameters.doubleParameter("mean")
                    return Exponential(mean)
                }

                RVType.Gamma -> {
                    val scale = parameters.doubleParameter("scale")
                    val shape = parameters.doubleParameter("shape")
                    Gamma(shape, scale)
                }

                RVType.GeneralizedBeta -> {
                    val alpha = parameters.doubleParameter("alpha")
                    val beta = parameters.doubleParameter("beta")
                    val min = parameters.doubleParameter("min")
                    val max = parameters.doubleParameter("max")
                    return GeneralizedBeta(alpha, beta, min, max)
                }

                RVType.Lognormal -> {
                    val mean = parameters.doubleParameter("mean")
                    val variance = parameters.doubleParameter("variance")
                    return Lognormal(mean, variance)
                }

                RVType.Normal -> {
                    val mean = parameters.doubleParameter("mean")
                    val variance = parameters.doubleParameter("variance")
                    return Normal(mean, variance)
                }

                RVType.Triangular -> {
                    val mode = parameters.doubleParameter("mode")
                    val min = parameters.doubleParameter("min")
                    val max = parameters.doubleParameter("max")
                    return Triangular(min, mode, max)
                }

                RVType.Uniform -> {
                    val min = parameters.doubleParameter("min")
                    val max = parameters.doubleParameter("max")
                    return Uniform(min, max)
                }

                RVType.Weibull -> {
                    val scale = parameters.doubleParameter("scale")
                    val shape = parameters.doubleParameter("shape")
                    return Weibull(shape, scale)
                }

                RVType.PearsonType5 -> {
                    val scale = parameters.doubleParameter("scale")
                    val shape = parameters.doubleParameter("shape")
                    return PearsonType5(shape, scale)
                }

                RVType.PearsonType6 -> {
                    val alpha1 = parameters.doubleParameter("shape1")
                    val alpha2 = parameters.doubleParameter("shape2")
                    val beta = parameters.doubleParameter("scale")
                    return PearsonType6(alpha1, alpha2, beta)
                }

                RVType.LogLogistic -> {
                    val scale = parameters.doubleParameter("scale")
                    val shape = parameters.doubleParameter("shape")
                    return LogLogistic(shape, scale)
                }

                RVType.Logistic -> {
                    val scale = parameters.doubleParameter("scale")
                    val location = parameters.doubleParameter("location")
                    return Logistic(location, scale)
                }

                RVType.ChiSquared -> {
                    val dof = parameters.doubleParameter("dof")
                    return ChiSquaredDistribution(dof)
                }

                RVType.Laplace -> {
                    val scale = parameters.doubleParameter("scale")
                    val location = parameters.doubleParameter("location")
                    return Laplace(location, scale)
                }

//                RVType.JohnsonB -> TODO("No distribution implemented yet")

                else -> null
            }
        }

        /**
         *  Constructs a frequency tabulation of the top distributions for each
         *  bootstrap sample of the original data.  This function can be used
         *  to explore the set of distributions that may best-fit the data.
         *
         *  @param data the original data
         *  @param evaluationMethod the evaluation method used to recommend a best-fitting family
         *  {Scoring, Ranking} Scoring is the default and uses the MODA highest overall value
         *  to make the recommendation. Ranking uses the average ranking across metrics to
         *  make the recommendation.  Lower (first) place ranks are preferred.
         *  @param estimators the set of estimators to use during the estimation process
         *  @param scoringModels the scoring models used during the estimation process
         *  @param numBootstrapSamples the number of bootstrap samples of the original data
         *  @param automaticShifting if true automatic shifting occurs, true is the default
         *  @param streamNum the random number stream to use for the bootstrapping process
         */
        fun bootstrapFamilyFrequency(
            data: DoubleArray,
            evaluationMethod: EvaluationMethod = EvaluationMethod.Scoring,
            estimators: Set<ParameterEstimatorIfc> = allEstimators,
            scoringModels: Set<PDFScoringModel> = defaultScoringModels,
            numBootstrapSamples: Int = 400,
            automaticShifting: Boolean = true,
            streamNum: Int = 0,
            streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider
        ): IntegerFrequency {
            val cdfFreq = IntegerFrequency(name = "Distribution Frequency")
            val estMap : MutableBiMap<RVParametersTypeIfc, Int> = HashBiMap()
            var cnt = 1
            for (estimator in estimators) {
                estMap[estimator.rvType] = cnt
                cnt = cnt + 1
            }
            val cellLabels = mutableMapOf<Int, String>()
            val invMap = estMap.inverse
            for ((i, rv) in invMap) {
                cellLabels[i] = rv.toString()
            }
            val bsPop = DPopulation(data, streamNum,streamProvider)
            for (i in 1..numBootstrapSamples) {
                val d = bsPop.sample(data.size)
                val pdfModeler = PDFModeler(d, scoringModels)
                val results = pdfModeler.estimateAndEvaluateScores(estimators, automaticShifting)
                val topRVType = if (evaluationMethod == EvaluationMethod.Scoring) {
                    results.topRVTypeByScore
                } else {
                    results.topRVTypeByRanking
                }
                val distNum = estMap[topRVType]!!
                cdfFreq.collect(distNum)
            }
            cdfFreq.assignCellLabels(cellLabels)
            return cdfFreq
        }

        /**
         *  Constructs a frequency tabulation of the top distributions for each
         *  bootstrap sample of the original data.  This function can be used
         *  to explore the set of distributions that may best-fit the data.
         *
         *  @param data the original data
         *  @param evaluationMethod the evaluation method used to recommend a best-fitting family
         *  {Scoring, Ranking} Scoring is the default and uses the MODA highest overall value
         *  to make the recommendation. Ranking uses the average ranking across metrics to
         *  make the recommendation.  Lower (first) place ranks are preferred.
         *  @param estimators the set of estimators to use during the estimation process
         *  @param scoringModels the scoring models used during the estimation process
         *  @param numBootstrapSamples the number of bootstrap samples of the original data
         *  @param automaticShifting if true automatic shifting occurs, true is the default
         *  @param streamNum the random number stream to use for the bootstrapping process
         */
        fun bootstrapFamilyFrequencyAsDataFrame(
            data: DoubleArray,
            evaluationMethod: EvaluationMethod = EvaluationMethod.Scoring,
            estimators: Set<ParameterEstimatorIfc> = allEstimators,
            scoringModels: Set<PDFScoringModel> = defaultScoringModels,
            numBootstrapSamples: Int = 400,
            automaticShifting: Boolean = true,
            streamNum: Int = 0,
            streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider
        ): AnyFrame {
            val freq = bootstrapFamilyFrequency(
                data, evaluationMethod,
                estimators, scoringModels, numBootstrapSamples, automaticShifting, streamNum, streamProvider
            )
            var df = freq.toDataFrame()
            df = df.remove("id", "name", "value")
            df = df.rename(Pair("cellLabel", "Distribution"))
            df = df.rename(Pair("count", "Ranked First"))
            return df
        }
    }
}
