package ksl.examples.general.utilities.fitting

import ksl.utilities.distributions.fitting.*
import ksl.utilities.distributions.fitting.estimators.*
import ksl.utilities.distributions.fitting.scoring.*
import ksl.utilities.io.KSL
import ksl.utilities.random.rvariable.GammaRV
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
    estimators : Set<ParameterEstimatorIfc> = defaultEstimators,
    scoringModels : Set<PDFScoringModel> = defaultScoringModels,
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

    private val myEstimators : Set<ParameterEstimatorIfc> = estimators
        get() {
            val set = mutableSetOf<ParameterEstimatorIfc>()
            for (element in field){
                set.add(element)
            }
            return set
        }

    private val myScoringModels : Set<PDFScoringModel> = scoringModels
        get() {
            val set = mutableSetOf<PDFScoringModel>()
            for (element in field){
                set.add(element)
            }
            return set
        }

    fun runCases() {
        for (case in cases) {
            for (sampleSize in case.sampleSizes){
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
            saveFittingResults(dfTestCase, sampleSize, i, pdfModelingResults)
        }
    }

    private fun saveCaseToDb(dfTestCase: DFTestCase){
        resultsDb.saveCase(dfTestCase)
        resultsDb.saveCaseParameters(dfTestCase)
    }

    private fun saveStatistics(caseID : Int, sampleSize: Int, sampleID: Int, statistic: Statistic){
        resultsDb.saveStatistics(caseID, sampleSize, sampleID, statistic)
    }

    private fun saveFittingResults(dfTestCase: DFTestCase, sampleSize: Int,
                                   sampleID: Int, results: PDFModelingResults){
        //TODO need to capture case results, need data and modeling results

    }

    companion object{
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

fun main(){

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
