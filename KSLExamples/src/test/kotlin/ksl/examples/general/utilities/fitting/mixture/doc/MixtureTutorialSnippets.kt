package ksl.examples.general.utilities.fitting.mixture.doc

import ksl.examples.general.utilities.fitting.mixture.receptionDeskComponents
import ksl.examples.general.utilities.fitting.mixture.receptionDeskData
import ksl.examples.general.utilities.fitting.mixture.receptionDeskFamilies
import ksl.examples.general.utilities.fitting.mixture.receptionDeskHoldOut
import ksl.examples.general.utilities.fitting.mixture.receptionDeskWeights
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.distributions.fitting.mixture.AdmissibilityCertificate
import ksl.utilities.distributions.fitting.mixture.ComponentFitCache
import ksl.utilities.distributions.fitting.mixture.DataPartition
import ksl.utilities.distributions.fitting.mixture.MixtureBootstrap
import ksl.utilities.distributions.fitting.mixture.MixtureModeler
import ksl.utilities.distributions.fitting.mixture.PDFComponentFitter
import ksl.utilities.distributions.fitting.mixture.RecoveryMeasures
import ksl.utilities.distributions.fitting.mixture.partition.JenksPartitionGenerator
import ksl.utilities.distributions.fitting.mixture.refine.BreakShiftRefiner
import ksl.utilities.distributions.fitting.mixture.refine.ClassificationEMRefiner
import ksl.utilities.distributions.fitting.mixture.scoring.MixtureBICCriterion
import ksl.utilities.distributions.fitting.mixture.search.SeparableCMLSelector
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.report.extensions.asHTML
import ksl.utilities.io.report.extensions.asMarkdown
import ksl.utilities.io.report.extensions.showHTMLInBrowser
import ksl.utilities.io.report.extensions.toReport

/**
 * Compile-only host for every code snippet in `docs/guides/ksl-mixture-tutorial.md`.
 *
 * Each function body below is a verbatim snippet from the tutorial, so compiling this file
 * proves every example in the guide references real public API. `MixtureTutorialSnippetsTest`
 * additionally proves the tutorial's blocks actually APPEAR here — without that second check
 * this file can compile perfectly while the tutorial shows an API that no longer exists, which
 * is a green check measuring nothing.
 *
 * This file is not run as a test; the build only needs to compile it.
 */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER", "unused")
private object MixtureTutorialSnippets {

    // -- Part I: the shipped data ---------------------------------------------

    fun readTheData() {
        val data = receptionDeskData()
        val holdOut = receptionDeskHoldOut()
    }

    fun readYourOwnData() {
        val file = KSLFileUtil.chooseFile() ?: return
        val data = KSLFileUtil.scanToArray(file.toPath())
    }

    // -- Part II: the whole thing in three lines -------------------------------

    fun theWholeThing() {
        val data = receptionDeskData()
        val modeler = MixtureModeler(data)
        val results = modeler.fit(numComponentsRange = 1..6)
        println(results)
    }

    fun componentByComponent() {
        val results = MixtureModeler(receptionDeskData()).fit(numComponentsRange = 1..6)
        val best = results.best ?: return
        val mixture = best.candidate
        for (j in 0 until mixture.numComponents) {
            println("component ${j + 1}: weight = ${mixture.weights[j]}, ${mixture.components[j].name}")
        }
    }

    fun theFitIsAnOrdinaryDistribution() {
        val results = MixtureModeler(receptionDeskData()).fit(numComponentsRange = 1..6)
        val fitted = results.best!!.distribution
        println(fitted.mean())
        println(fitted.invCDF(0.5))
        val generated = fitted.randomVariable(streamNumber = 99).sample(5)
    }

    // -- Part III: choosing the number of components ---------------------------

    fun everyCriterionAtOnce() {
        val results = MixtureModeler(receptionDeskData()).fit(numComponentsRange = 1..6)
        println(results.criterionSummary())
    }

    fun scoreAtEachComponentCount() {
        val results = MixtureModeler(receptionDeskData()).fit(numComponentsRange = 1..6)
        for ((k, ranked) in results.bestByNumComponents().toSortedMap()) {
            println("k = $k: ${ranked.criterionValue.value}")
        }
    }

    // -- Part IV: assembling the pipeline by hand ------------------------------

    fun theFacadeTakenApart() {
        val sorted = receptionDeskData().sortedArray()
        val k = 3
        val certificate = AdmissibilityCertificate(sorted)
        val fitter = ComponentFitCache(PDFComponentFitter(PDFModeler.allEstimators, false))
        val criterion = MixtureBICCriterion()
        val selector = SeparableCMLSelector()
        val initial = JenksPartitionGenerator(sorted, k).generate(sorted, k, certificate) ?: return
    }

    fun refineTwoWays(sorted: DoubleArray, initial: DataPartition, certificate: AdmissibilityCertificate) {
        val fitter = ComponentFitCache(PDFComponentFitter(PDFModeler.allEstimators, false))
        val shifted = BreakShiftRefiner().refine(sorted, initial, certificate, fitter)
        val classified = ClassificationEMRefiner().refine(sorted, initial, certificate, fitter)
    }

    // -- Part V: judging the answer -------------------------------------------

    fun againstAKnownTruth() {
        val results = MixtureModeler(receptionDeskData()).fit(numComponentsRange = 1..6)
        val mixture = results.best!!.candidate
        val recovery = RecoveryMeasures.compare(
            receptionDeskWeights, receptionDeskComponents, receptionDeskFamilies,
            mixture.weights, mixture.distributions, mixture.components.map { it.rvType }
        )
        println(recovery.hellinger)
        println(recovery.numComponentsCorrect)
    }

    fun onDataItHasNotSeen() {
        val results = MixtureModeler(receptionDeskData()).fit(numComponentsRange = 1..6)
        val mixture = results.best!!.candidate
        val evaluation = mixture.logLikelihood(receptionDeskHoldOut())
        if (evaluation.isUsable) {
            println(evaluation.value / receptionDeskHoldOut().size)
        }
    }

    // -- Part VI: the diagnostics ---------------------------------------------

    fun showEverything() {
        val data = receptionDeskData()
        val holdOut = receptionDeskHoldOut()
        val results = MixtureModeler(data).fit(numComponentsRange = 1..6)
        results.showAllResultsInBrowser(heldOut = holdOut)
        val report = results.showHTMLInBrowser(heldOut = holdOut)
    }

    fun resultsAsData() {
        val results = MixtureModeler(receptionDeskData()).fit(numComponentsRange = 1..6)
        println(results.componentsAsDataFrame())
        println(results.criterionProfileAsDataFrame())
    }

    // -- The standard report ---------------------------------------------------

    fun theOneLineReport() {
        val results = MixtureModeler(receptionDeskData()).fit(numComponentsRange = 1..6)
        results.showHTMLInBrowser(heldOut = receptionDeskHoldOut())
    }

    fun theReportWithoutHeldOutData() {
        val results = MixtureModeler(receptionDeskData()).fit(numComponentsRange = 1..6)
        results.showHTMLInBrowser()
    }

    fun theWholeStoryInOneReport() {
        val data = receptionDeskData()
        val modeler = MixtureModeler(data)
        val results = modeler.fit(numComponentsRange = 1..6)
        val report = results.showHTMLInBrowser(
            heldOut = receptionDeskHoldOut(),
            title = "Reception desk service times",
            description = modeler.describe(),
            stability = modeler.partitionStability(results),
            adequacy = modeler.assessFitAdequacy(results),
            bootstrap = MixtureBootstrap.componentCountFrequency(data, numBootstrapSamples = 100),
            variableName = "service time (minutes)"
        )
        println(report.absolutePath)
    }

    fun keepTheReportRatherThanShowIt() {
        val results = MixtureModeler(receptionDeskData()).fit(numComponentsRange = 1..6)
        val html = results.asHTML(heldOut = receptionDeskHoldOut())
        val markdown = results.asMarkdown(heldOut = receptionDeskHoldOut())
    }

    fun addYourOwnSectionToTheReport() {
        val results = MixtureModeler(receptionDeskData()).fit(numComponentsRange = 1..6)
        val document = results.toReport(title = "Reception desk service times") {
            heading("Why we fitted this", level = 2)
            paragraph("Three kinds of visitor share one queue, so one distribution will not do.")
        }
    }

    // -- Part VII: how much to believe it -------------------------------------

    fun resampleTheData() {
        val data = receptionDeskData()
        val boot = MixtureBootstrap.componentCountFrequency(
            data,
            numBootstrapSamples = 100,
            numComponentsRange = 1..6
        )
        println(boot)
    }

    fun resampleTheFit() {
        val data = receptionDeskData()
        val results = MixtureModeler(data).fit(numComponentsRange = 1..6)
        val parametric = MixtureBootstrap.parametricComponentCountFrequency(
            results.best!!.distribution,
            sampleSize = data.size,
            numBootstrapSamples = 100,
            numComponentsRange = 1..6
        )
        println(parametric)
    }
}
