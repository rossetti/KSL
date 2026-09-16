package ksl.utilities.io.report.extensions

import ksl.utilities.distributions.fitting.mixture.receptionDeskData
import ksl.utilities.distributions.fitting.mixture.receptionDeskHoldOut
import ksl.utilities.distributions.fitting.mixture.AdequacyVerdict
import ksl.utilities.distributions.fitting.mixture.FitAdequacy
import ksl.utilities.distributions.fitting.mixture.FitVerdict
import ksl.utilities.distributions.fitting.mixture.GainVersusPenaltyPlot
import ksl.utilities.distributions.fitting.mixture.MixtureModeler
import ksl.utilities.distributions.fitting.mixture.PartitionCutPlot
import ksl.utilities.distributions.fitting.mixture.SampleDotPlot
import ksl.utilities.distributions.fitting.mixture.MixtureModelingResults
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.toHtml
import ksl.utilities.io.report.toMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  Covers the mixture report extensions.
 *
 *  Assertions are against the node tree rather than rendered output. A test that greps HTML breaks
 *  on every styling change and passes on an empty table, which is the wrong way round: the thing
 *  worth pinning is that the content is present and correct, not how a renderer dressed it.
 */
class MixtureModelerReportExtensionsTest {

    private val data = receptionDeskData()
    private val holdOut = receptionDeskHoldOut()

    private val searched: MixtureModelingResults by lazy {
        MixtureModeler(data).fit(numComponentsRange = 1..4)
    }

    /**
     *  A sample whose component count nothing could settle: two normals barely a deviation apart,
     *  at a size that puts the difficulty far below the measured even-odds point.
     */
    private val inadequate: Pair<MixtureModeler, MixtureModelingResults> by lazy {
        val small = NormalRV(0.0, 1.0, streamNum = 771).sample(60) +
                NormalRV(0.8, 1.0, streamNum = 772).sample(60)
        val modeler = MixtureModeler(small)
        modeler to modeler.fit(numComponents = 2)
    }

    // -------------------------------------------------------------- tree helpers

    private fun ReportNode.descendants(): List<ReportNode> = when (this) {
        is ReportNode.Document -> children.flatMap { listOf(it) + it.descendants() }
        is ReportNode.Section -> children.flatMap { listOf(it) + it.descendants() }
        else -> emptyList()
    }

    private fun ReportNode.sections(): List<ReportNode.Section> =
        descendants().filterIsInstance<ReportNode.Section>()

    private fun ReportNode.tables(): List<ReportNode.DataTable> =
        descendants().filterIsInstance<ReportNode.DataTable>()

    private fun ReportNode.plots(): List<ReportNode.PlotNode> =
        descendants().filterIsInstance<ReportNode.PlotNode>()

    private fun ReportNode.paragraphs(): List<String> =
        descendants().filterIsInstance<ReportNode.Paragraph>().map { it.text }

    private fun ReportNode.tableWithCaption(fragment: String): ReportNode.DataTable? =
        tables().firstOrNull { it.caption?.contains(fragment, ignoreCase = true) == true }

    // -------------------------------------------------------------- document shape

    @Test
    fun theStandardDocumentCarriesEverySection() {
        val doc = searched.toReport(heldOut = holdOut, includePlots = false)
        val titles = doc.sections().map { it.title }
        // "Criterion behaviour" is no longer among them: the profile it carried now sits inside
        // "Evidence about the number of components", since both tabulated criteria across
        // candidate counts and a reader had to compare two tables to answer one question.
        for (expected in listOf(
            "The sample", "Was a mixture worth it?", "The recommended mixture",
            "Goodness of fit", "Evidence about the number of components",
            "What should make you doubt this"
        )) {
            assertTrue(
                titles.any { it?.contains(expected) == true },
                "expected a section for '$expected', found $titles"
            )
        }
    }

    @Test
    fun theTitleIsCarriedThrough() {
        val doc = searched.toReport(title = "Service times", includePlots = false)
        assertEquals("Service times", doc.title)
    }

    @Test
    fun everyTableHasRowsMatchingItsHeaders() {
        // Guards the failure mode this test style exists to catch: a table that renders as a
        // heading with nothing under it, or with ragged rows a renderer will silently truncate.
        val doc = searched.toReport(heldOut = holdOut, includePlots = false)
        val tables = doc.tables()
        assertTrue(tables.size >= 6, "expected several tables, found ${tables.size}")
        for (table in tables) {
            assertTrue(table.rows.isNotEmpty(), "empty table: ${table.caption}")
            for (row in table.rows) {
                assertEquals(
                    table.headers.size, row.size,
                    "ragged row in '${table.caption}': $row against ${table.headers}"
                )
            }
        }
    }

    @Test
    fun theExtraContentLambdaIsAppended() {
        val doc = searched.toReport(includePlots = false) {
            section("Notes from the analyst") { paragraph("Collected on a Tuesday.") }
        }
        assertTrue(doc.sections().any { it.title == "Notes from the analyst" })
        assertTrue(doc.paragraphs().any { it.contains("Tuesday") })
    }

    // ------------------------------------------------- what may not reach the analyst

    /**
     *  Phrases that report an outcome of the designed experiment rather than a fact about the
     *  reader's own fit. Each was present in the shipped report before this guard existed.
     */
    private val experimentFindings = listOf(
        "11,520", "192 cases", "attempts in ten", "fits in five", "fits in four",
        "recovered on", "recovery", "saturates", "base rate", "median gap",
        "57.4%", "56.9%", "99.0%", "33.7%", "77%", "98%", "36.6%", "22.0%"
    )

    /**
     *  The report describes the reader's fit and nothing else.
     *
     *  **The constraint this test exists to stop eroding.** An analyst has one sample and one fit.
     *  Aggregate results over 11,520 synthetic attempts are a finding about the method, they belong
     *  in the project report, and they are not evidence about the data in front of them. Before
     *  this guard the rendered document carried five mentions of the designed experiment, four
     *  recovery percentages and an attempt count.
     *
     *  Scanned as rendered text rather than against the tree, unlike the rest of this file, because
     *  the property under test *is* the text: a phrase is equally wrong in a paragraph, a table
     *  cell and a caption, and a tree walk that forgot one node type would pass while the reader
     *  still saw it.
     */
    @Test
    fun noSectionReportsAFindingFromTheDesignedExperiment() {
        val modeler = MixtureModeler(data)
        val stability = modeler.partitionStability(searched)
        val text = searched.toReport(heldOut = holdOut, stability = stability, includePlots = true)
            .toMarkdown()
        val found = experimentFindings.filter { text.contains(it, ignoreCase = true) }
        assertTrue(
            found.isEmpty(),
            "the analyst's report must describe this fit only, but it reports $found"
        )
    }

    /**
     *  A calibrated reading may say that it is calibrated, and may not say what the calibration
     *  found.
     *
     *  `CountAdequacy` and `PartitionStability` give readings whose thresholds were fitted to the
     *  designed experiment rather than derived, so suppressing that entirely would let a rule of
     *  thumb read like a theorem. One sentence of provenance is allowed; a number in it would be a
     *  finding, so the rule is that the sentence carries no digits.
     */
    @Test
    fun aProvenanceSentenceStatesTheStatusOfAReadingAndNoResultOfIt() {
        val modeler = MixtureModeler(data)
        val stability = modeler.partitionStability(searched)
        val text = searched.toReport(heldOut = holdOut, stability = stability, includePlots = false)
            .toMarkdown()
        val offending = text.split(Regex("(?<=[.!?])\\s+"))
            .filter { it.contains("designed experiment", ignoreCase = true) }
            .filter { it.any(Char::isDigit) }
        assertTrue(
            offending.isEmpty(),
            "a provenance sentence must carry no numbers, but found: $offending"
        )
    }

    // ----------------------------------------------------------------- fit adequacy

    /**
     *  Cheap enough to run in a test: a handful of replicates says nothing about a fit, but it
     *  exercises every path the report takes through the assessment.
     */
    private val adequacy by lazy {
        MixtureModeler(data).let { m ->
            val r = m.fit(numComponentsRange = 1..4)
            m.assessFitAdequacy(
                baseline = r, numReplicates = 9, streamNum = 1,
                streamProvider = RNStreamProvider(name = "report-test-fit-adequacy")
            )
        }
    }

    @Test
    fun theAdequacySectionIsAbsentUnlessAnAssessmentIsSupplied() {
        val without = searched.toReport(includePlots = false)
        assertFalse(
            without.sections().any { it.title?.contains("fitted density adequate") == true },
            "the assessment refits the model many times; it must not run behind a formatting call"
        )
        val with = searched.toReport(includePlots = false, adequacy = adequacy)
        assertTrue(
            with.sections().any { it.title?.contains("fitted density adequate") == true },
            "supplying an assessment must produce its section: ${with.sections().map { it.title }}"
        )
    }

    @Test
    fun theSummariesComeBeforeTheVerdictAndCarryARelativeError() {
        val a = adequacy
        assertNotNull(a, "the reception desk fit can be assessed")
        val doc = searched.toReport(includePlots = false, adequacy = a)
        val table = doc.tableWithCaption("beside the same summaries of the fit")
        assertNotNull(table, "the functional table must be present")
        assertEquals(a.functionals.size, table.rows.size, "one row per functional")
        assertTrue(
            table.rows.all { it[3].endsWith("%") || it[3] == "—" },
            "each row must carry a relative error: ${table.rows}"
        )
        // Above the verdict, because the summaries are what an input modeller acts on and a
        // reader stops at the first thing that looks like an answer.
        val captions = doc.tables().mapNotNull { it.caption }
        val summaries = captions.indexOfFirst { it.contains("beside the same summaries") }
        val verdict = captions.indexOfFirst { it.contains("put to the same work") }
        assertTrue(summaries in 0..<verdict, "summaries $summaries must precede verdict $verdict")
    }

    @Test
    fun aContradictedDensityLeadsTheDocument() {
        // Constructed rather than found: a fit whose own data cannot have come from it. The lead
        // exists so that a reader cannot reach the recommendation without meeting this first.
        val a = FitAdequacy(
            numObservations = 400,
            observedStatistic = 0.9,
            nullStatistics = DoubleArray(99) { 0.01 * (it + 1) / 99.0 },
            numReplicates = 99,
            functionals = emptyList()
        )
        assertEquals(FitVerdict.CONTRADICTED, a.verdict, "the fixture must be contradicted")
        val doc = searched.toReport(includePlots = false, adequacy = a)
        val lead = doc.sections().firstOrNull { it.title == "Read this first" }
        assertNotNull(lead, "a contradicted density must lead: ${doc.sections().map { it.title }}")
        assertTrue(
            lead.paragraphs().any { it.contains("does not describe this sample") },
            "the lead must say what is wrong: ${lead.paragraphs()}"
        )
    }

    @Test
    fun theOverlayIsDrawnBesideTheDiagnosticPanels() {
        val doc = searched.toReport(includePlots = true, adequacy = adequacy)
        val captions = doc.plots().mapNotNull { it.caption }
        assertTrue(
            captions.any { it.contains("drawn from the fit") },
            "the data against a draw from the fit must be shown: $captions"
        )
    }

    // -------------------------------------------------------------- fragment content

    @Test
    fun theSampleSectionReportsTheStructuralFacts() {
        val doc = searched.toReport(includePlots = false)
        val table = doc.tableWithCaption("Structural facts")
        assertTrue(table != null, "the certificate's facts must be reported")
        val labels = table!!.rows.map { it[0] }
        assertTrue(labels.contains("Distinct values"), labels.toString())
        assertTrue(labels.contains("Largest feasible number of groups"), labels.toString())
    }

    @Test
    fun theComponentTableHasOneRowPerComponent() {
        val doc = searched.toReport(includePlots = false)
        val table = doc.tableWithCaption("Components of the recommended mixture")
        assertTrue(table != null)
        assertEquals(searched.recommendedNumComponents, table!!.rows.size)
    }

    @Test
    fun theCriterionProfileHasAColumnPerCandidateCount() {
        val doc = searched.toReport(includePlots = false)
        val table = doc.tableWithCaption("Criterion value at each number of components")
        assertTrue(table != null)
        val counts = searched.bestByNumComponents().keys
        assertEquals(counts.size + 1, table!!.headers.size, "one label column plus one per count")
        // Derived rather than hard-coded: the reported criteria are a configurable list, and a
        // literal here fails whenever one is added for reasons that have nothing to do with the
        // report. What this test is about is that every reported criterion gets a row.
        val reported = MixtureModeler.defaultReportedCriteria(searched.catalogSize).size
        assertEquals(reported, table.rows.size, "one row per reported criterion")
    }

    @Test
    fun theComparisonAgainstASingleDistributionIsPresent() {
        val doc = searched.toReport(includePlots = false)
        val table = doc.tableWithCaption("Side by side")
        assertTrue(table != null)
        assertEquals(listOf("", "Single distribution", "Mixture"), table!!.headers)
        assertTrue(table.rows.any { it[0] == "Free parameters" })
        assertTrue(doc.paragraphs().any { it.startsWith("Verdict:") }, doc.paragraphs().toString())
    }

    @Test
    fun theDoubtsSectionReportsCoverageOnlyWhenHeldOutDataIsSupplied() {
        val withHoldOut = searched.toReport(heldOut = holdOut, includePlots = false)
            .tableWithCaption("Checks worth reading")!!
        assertTrue(
            withHoldOut.rows.any { it[0] == "Held-out coverage" },
            "coverage must be reported when data is supplied"
        )
        val without = searched.toReport(includePlots = false)
            .tableWithCaption("Checks worth reading")!!
        assertFalse(
            without.rows.any { it[0] == "Held-out coverage" },
            "coverage must not be invented when no data is supplied"
        )
    }

    // -------------------------------------------------------------- plots

    @Test
    fun plotsAreIncludedByDefaultAndOmittedOnRequest() {
        assertTrue(searched.toReport().plots().isNotEmpty())
        assertTrue(searched.toReport(includePlots = false).plots().isEmpty())
    }

    @Test
    fun bothFitsAreDrawnWhenAsked() {
        // The four standard panels for each fit, plus the data-against-a-draw overlay on the
        // mixture, which the single distribution has no counterpart for. Counted within those two
        // sections rather than over the document, so that a figure added elsewhere -- the
        // gain-against-penalty plot, say -- neither breaks this nor silently satisfies it.
        val doc = searched.toReport()
        val panels = doc.sections()
            .filter { it.title?.startsWith("Diagnostic plots") == true }
        assertEquals(2, panels.size, doc.sections().map { it.title }.toString())
        val mixture = panels.first { it.title?.contains("recommended mixture") == true }
        val single = panels.first { it.title?.contains("single distribution") == true }
        assertEquals(5, mixture.plots().size, "four panels and the overlay")
        assertEquals(4, single.plots().size, "four panels")
    }

    // -------------------------------------------------------------- the bootstrap

    @Test
    fun theBootstrapSectionIsAbsentUnlessSupplied() {
        // The report must never start resampling on its own: it refits the whole model many times,
        // and expensive work begun by a call that looks like formatting is a surprise.
        val doc = searched.toReport(includePlots = false)
        assertFalse(
            doc.sections().any { it.title?.contains("resampling", ignoreCase = true) == true },
            "no bootstrap was supplied, so no reliability section may appear"
        )
    }

    // -------------------------------------------------------------- rendering

    @Test
    fun theHtmlIsASelfContainedDocumentOfRealTables() {
        // What the hand-built page could not do. The old form wrapped the console summary in one
        // preformatted block, so its content was characters rather than structure; a renderer had
        // nothing to work with and the Markdown and LaTeX forms did not exist at all.
        val html = searched.asHTML(heldOut = holdOut, includePlots = false)
        assertTrue(html.startsWith("<!DOCTYPE html>"), html.take(80))
        assertTrue(html.contains("<title>"), "a complete document carries its own head")
        val tables = Regex("<table").findAll(html).count()
        assertTrue(tables >= 6, "expected the sections' tables to be tables, found $tables")
        assertFalse(
            html.contains("<pre"),
            "no part of the report may be dumped as preformatted text"
        )
    }

    @Test
    fun theMarkdownCarriesTheSameContent() {
        val markdown = searched.asMarkdown(heldOut = holdOut)
        assertTrue(markdown.contains("# Mixture Modeling Results"), markdown.take(120))
        for (heading in listOf("The sample", "Was a mixture worth it?", "Goodness of fit")) {
            assertTrue(markdown.contains(heading), "missing '$heading'")
        }
        // Pipe-delimited rows are the renderer's tables; the criterion profile must be one.
        assertTrue(markdown.contains("|Criterion|"), "the profile must render as a table")
    }

    @Test
    fun markdownOmitsPlotsByDefault() {
        // Markdown cannot carry an embedded interactive plot, so including them would leave
        // captions with nothing underneath.
        assertFalse(searched.asMarkdown().contains("Quantile-quantile"))
        assertTrue(searched.asHTML().contains("Quantile-quantile"))
    }

    @Test
    fun theTitleReachesTheRenderedOutput() {
        assertTrue(searched.asMarkdown(title = "Service times").contains("# Service times"))
        assertTrue(searched.asHTML(title = "Service times", includePlots = false)
            .contains("<title>Service times</title>"))
    }

    // -------------------------------------------------------------- specified counts

    @Test
    fun aSpecifiedCountIsReportedAsSpecified() {
        val specified = MixtureModeler(data).fit(numComponents = 3)
        val doc = specified.toReport(includePlots = false)
        assertTrue(
            doc.paragraphs().any { it.contains("specified rather than selected") },
            doc.paragraphs().toString()
        )
        val table = doc.tableWithCaption("What each criterion prefers")
        assertTrue(table != null, "a specified count is preferred among, not chosen from")
        assertTrue(
            table!!.rows.all { it[2] == "—" },
            "no boundary annotation applies when the counts were specified: ${table.rows}"
        )
    }

    @Test
    fun aSearchStillReportsWhereTheMinimumSits() {
        val doc = searched.toReport(includePlots = false)
        val table = doc.tableWithCaption("What each criterion chose")
        assertTrue(table != null, "a search reports whether each criterion chose at all")
        assertTrue(
            table!!.rows.any { it[2] == "interior" || it[2].contains("count fitted") },
            "the minimum's location must be classified: ${table.rows}"
        )
    }

    @Test
    fun severalSpecifiedCountsAreAllReported() {
        val specified = MixtureModeler(data).fitSpecified(setOf(2, 3))
        val doc = specified.toReport(includePlots = false)
        assertTrue(specified.recommendedNumComponents in setOf(2, 3))
        assertTrue(
            doc.paragraphs().any { it.contains("2, 3") },
            "both requested counts must be named: ${doc.paragraphs()}"
        )
        val profile = doc.tableWithCaption("Criterion value at each number")!!
        // The one-component baseline is fitted alongside, so three counts appear.
        assertEquals(4, profile.headers.size, profile.headers.toString())
    }

    // -------------------------------------------------------------- gate F

    @Test
    fun theWorkflowSectionsAppearInTheOrderAnAnalystNeedsThem() {
        // The order is the argument. What was known before fitting comes before the fit; whether
        // the sample can settle the count comes before the count; the evidence about the count
        // comes before the criterion detail that would otherwise read as a decision.
        val modeler = MixtureModeler(data)
        val doc = searched.toReport(
            heldOut = holdOut,
            includePlots = false,
            description = modeler.describe(numBootstrapSamples = 20, streamProvider = RNStreamProvider()),
            stability = modeler.partitionStability(searched)
        )
        val titles = doc.sections().map { it.title }
        val order = listOf(
            "Before fitting anything",
            "The sample",
            "Can this sample settle the component count?",
            "Was a mixture worth it?",
            "Evidence about the number of components",
            "Does the fit depend on where the cuts fell?"
        )
        val positions = order.map { expected ->
            val at = titles.indexOfFirst { it != null && it.contains(expected) }
            assertTrue(at >= 0, "the report is missing the '$expected' section; it had $titles")
            at
        }
        assertEquals(
            positions.sorted(), positions,
            "the sections are out of order: $titles"
        )
    }

    @Test
    fun aReportOnAnInadequateSampleLeadsWithThatFact() {
        val (modeler, results) = inadequate
        assertEquals(
            AdequacyVerdict.INSUFFICIENT, results.countAdequacy()?.verdict,
            "this fixture exists to be inadequate; if it is not, the test proves nothing"
        )
        val doc = results.toReport(includePlots = false)
        val titles = doc.sections().map { it.title }
        assertEquals(
            "Read this first", titles.first(),
            "an inadequate sample must be told about before anything else; sections were $titles"
        )
        val lead = doc.sections().first().paragraphs()
        assertTrue(
            lead.any { it.contains("cannot settle how many components") },
            "the leading section must say plainly what is wrong: $lead"
        )
        assertTrue(
            lead.any { it.contains("still worth reading as a description") },
            "and must say what the report is still good for"
        )
        // The count is never suppressed -- O8 says report, do not recommend or refuse.
        assertTrue(modeler.maximumFeasibleComponents >= 2)
        assertEquals(2, results.recommendedNumComponents)
    }

    @Test
    fun anAdequateSampleGetsNoLeadingWarning() {
        val doc = searched.toReport(heldOut = holdOut, includePlots = false)
        assertFalse(
            doc.sections().any { it.title == "Read this first" },
            "the leading warning must fire only when the sample cannot settle the count"
        )
    }

    @Test
    fun theEvidenceTableLabelsTheMarginAsFirmnessAndSaysWhatItIsNot() {
        val doc = searched.toReport(includePlots = false)
        val table = doc.tableWithCaption("what each count buys")
        assertTrue(table != null, "the evidence table is missing")
        assertTrue(
            table.headers.any { it.contains("Firmness") },
            "the margin must appear under its corrected label, not as reliability: ${table.headers}"
        )
        assertFalse(
            table.headers.any { it.contains("Reliab", ignoreCase = true) },
            "nothing here measures reliability"
        )
        // The disclaimer used to rest on a designed-experiment finding — that the criterion is
        // "confidently wrong when it is wrong". The claim still has to be made, but to an analyst
        // reading about their own fit it has to be made without importing that result, so what is
        // pinned here is the meaning rather than the sentence that used to carry it.
        assertTrue(
            doc.paragraphs().any {
                it.contains("Firmness is how far") &&
                        it.contains("not a measure of how likely") &&
                        it.contains("not evidence that the count is right")
            },
            "the report must say what firmness is not, since a reader will otherwise assume: " +
                    doc.paragraphs()
        )
    }

    @Test
    fun everyReportedCriterionReachesTheEvidenceTable() {
        // Derived rather than hard-coded, so adding a criterion cannot silently drop it.
        val doc = searched.toReport(includePlots = false)
        val table = doc.tableWithCaption("what each count buys")!!
        for (criterion in MixtureModeler.defaultReportedCriteria(searched.catalogSize)) {
            assertTrue(
                table.headers.any { it.contains(criterion.name) },
                "criterion ${criterion.name} is reported but absent from the evidence table: " +
                        "${table.headers}"
            )
        }
    }

    @Test
    fun theCutDependenceSectionCarriesItsCalibrationCaveat() {
        val modeler = MixtureModeler(data)
        val stability = modeler.partitionStability(searched)
        assertTrue(stability != null, "the reception desk fit has cuts to move")
        val doc = searched.toReport(includePlots = false, stability = stability)
        val paragraphs = doc.sections()
            .first { it.title?.contains("where the cuts fell") == true }
            .paragraphs()
        assertTrue(
            paragraphs.any { it.contains("designed experiment") },
            "a calibrated reading must say what it was calibrated on: $paragraphs"
        )
        assertTrue(
            paragraphs.any { it.contains("rule of thumb rather than a theorem") },
            "and must not read as a law"
        )
    }

    @Test
    fun aWarnedSampleRendersAndCarriesItsWarning() {
        // Gate F's third case: unimodal data with no claimed mechanism. The gate warns and
        // proceeds; a report that refused to render would be the refusal the design rules out.
        val unimodal = NormalRV(0.0, 1.0, streamNum = 781).sample(300)
        val modeler = MixtureModeler(unimodal)
        val results = modeler.fit(numComponentsRange = 1..3)
        val description = modeler.describe(
            numBootstrapSamples = 30, streamProvider = RNStreamProvider()
        )
        val html = results.asHTML(includePlots = false, description = description)
        assertTrue(html.contains("<table"), "the report must still render for a warned sample")
        val doc = results.toReport(includePlots = false, description = description)
        val before = doc.sections().first { it.title == "Before fitting anything" }.paragraphs()
        assertTrue(
            before.any { it.contains("Neither warrant") } ||
                    before.any { it.contains("warrant for a mixture is present") },
            "the entry gate's reading must appear: $before"
        )
        assertFalse(
            doc.paragraphs().any { it.contains("refus", ignoreCase = true) },
            "the gate warns and proceeds; nothing in the report may read as a refusal"
        )
    }

    @Test
    fun aSpecifiedCountAndASearchedRangeBothRenderInFull() {
        val modeler = MixtureModeler(data)
        val specified = modeler.fit(numComponents = 3)
        for (results in listOf(specified, searched)) {
            val html = results.asHTML(heldOut = holdOut, includePlots = false)
            assertTrue(html.contains("<table"), "the report must render tables")
            assertTrue(
                results.toReport(includePlots = false).sections().size >= 6,
                "every standard section must be present"
            )
        }
        assertTrue(specified.numComponentsWasSpecified)
        assertFalse(searched.numComponentsWasSpecified)
    }

    // -------------------------------------------------------------- the partition figures

    @Test
    fun theSampleSectionDrawsTheDataWhenPlotsAreAsked() {
        // Asserted by caption rather than by a count. A count is satisfied by the nine plots the
        // evidence and diagnostic sections already contribute, so it passes whether or not this
        // figure is wired at all -- which is exactly what a mutation of the wiring showed.
        fun captions(includePlots: Boolean) =
            searched.toReport(includePlots = includePlots).plots().mapNotNull { it.caption }

        assertTrue(
            captions(true).any { it.contains("before any model touches it") },
            "the sample figure must be drawn; captions were ${captions(true)}"
        )
        assertFalse(
            captions(false).any { it.contains("before any model touches it") },
            "no figure may be drawn when plots were not asked for"
        )
    }

    @Test
    fun theTiedValueFigureIsDrawnOnlyWhenTiesActuallyRuleAPositionOut() {
        // The certificate rules a cut position out only when it sits inside a run of equal values.
        // On continuous measurements nothing is ruled out, and a figure in which every mark is the
        // same colour reads as broken rather than as "no ties" -- so it is skipped.
        val continuous = searched.toReport(includePlots = true).plots().mapNotNull { it.caption }
        assertFalse(
            continuous.any { it.contains("equal values must stay together") },
            "with 400 distinct values nothing is refused, so the figure must be skipped"
        )

        // The same data rounded to the nearest half minute, which creates the runs.
        val tied = data.map { kotlin.math.round(it * 2.0) / 2.0 }.toDoubleArray()
        val tiedResults = MixtureModeler(tied).fit(1..3)
        val withTies = tiedResults.toReport(includePlots = true).plots().mapNotNull { it.caption }
        assertTrue(
            withTies.any { it.contains("equal values must stay together") },
            "rounding creates ties, so the figure must appear; captions were $withTies"
        )
    }

    @Test
    fun theCutDependenceSectionDrawsWhereTheCutsFell() {
        // The figure is the arrangement the three-valued verdict is about. Without results the
        // section cannot draw it and must still render.
        val modeler = MixtureModeler(data)
        val stability = modeler.partitionStability(searched)
        assertNotNull(stability)
        val withFigure = report("t") {
            mixtureCutDependence(stability, results = searched)
        }
        val withoutFigure = report("t") { mixtureCutDependence(stability) }
        assertEquals(1, withFigure.plots().size, "the cuts must be drawn when results are supplied")
        assertEquals(0, withoutFigure.plots().size, "no results, no figure, and no failure")
        assertTrue(withoutFigure.paragraphs().isNotEmpty(), "the verdict must survive either way")
    }

    @Test
    fun thePartitionFiguresRenderInEveryFormat() {
        val doc = searched.toReport(includePlots = true, stability = null)
        // HTML embeds the plot; Markdown and text write a file reference. All three must produce
        // something rather than failing on a plot node.
        assertTrue(doc.toHtml().contains("<table"), "HTML must render")
        assertTrue(doc.toMarkdown().isNotBlank(), "Markdown must render")
    }

    // -------------------------------------------------------------- the chi-squared note

    @Test
    fun noFigureCarriesACaptionDrawnInsideTheImage() {
        // The plot library does not wrap a caption, so anything long enough to matter is rendered
        // as one line that runs off the canvas and is clipped mid-word. Every destination for
        // these figures already carries a caption outside the image, so none is drawn inside one.
        val evidence = searched.componentCountEvidence()
        assertTrue(GainVersusPenaltyPlot(evidence).caption.isBlank())
        assertTrue(SampleDotPlot(data).caption.isBlank())
        assertTrue(PartitionCutPlot(data, emptyMap()).caption.isBlank())
    }

    @Test
    fun theFirmnessCaveatIsPrintedBesideTheFigureThatNeedsIt() {
        // It used to be drawn on the figure so it would travel with it, and was clipped instead.
        val text = searched.toReport(includePlots = true).paragraphs().joinToString(" ")
        assertTrue(
            text.contains("not how likely that view is to be right"),
            "the firmness caveat must reach the page now that the figure does not carry it"
        )
    }

    @Test
    fun theGoodnessOfFitSectionReportsTheSingleDistributionBesideTheMixture() {
        // The section's own prose says these numbers are most useful next to the same tests on the
        // single distribution. It must therefore supply them rather than send the reader looking.
        val table = searched.toReport(includePlots = false)
            .tableWithCaption("against the best single distribution")
        assertNotNull(table, "the comparison table must be present")
        assertTrue(table.headers.any { it.contains("Single") }, "headers were ${table.headers}")
        assertEquals(3, table.rows.size, "one row per test")
        for (row in table.rows) {
            assertEquals(
                table.headers.size, row.size,
                "every row must carry a value for the single distribution too: $row"
            )
        }
    }

    @Test
    fun theGoodnessOfFitSectionSaysWhyChiSquaredIsAbsent() {
        // Chi-squared is the only one of the four tests that adjusts for estimated parameters, and
        // it is omitted because a mixture with bounded components leaves bins with no expected
        // count. A reader comparing this against the single-distribution report will notice the
        // absence, and the report must answer them rather than leave it unexplained.
        val text = searched.toReport(includePlots = false).paragraphs().joinToString(" ")
        assertTrue(
            text.contains("chi-squared", ignoreCase = true),
            "the omission of chi-squared must be stated, not silent"
        )
        assertTrue(
            text.contains("bounded support") || text.contains("bounded components"),
            "the stated reason must be the support gap, which is the actual reason"
        )
        assertTrue(
            text.contains("adjusts", ignoreCase = true) ||
                    text.contains("adjust for", ignoreCase = true),
            "the report must say the three shown tests do not adjust for estimated parameters"
        )
    }
}
