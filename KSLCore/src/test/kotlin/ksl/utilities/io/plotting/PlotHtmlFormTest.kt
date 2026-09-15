package ksl.utilities.io.plotting

import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.renderer.HtmlReportRenderer
import ksl.utilities.io.report.renderer.RenderContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * A plot has two HTML forms and they are not interchangeable.
 *
 * [PlotIfc.toEmbeddedHTML] is a **fragment** for compositing into a page that has already loaded the
 * Lets-Plot library; [PlotIfc.toSelfContainedHTML] is a **complete document** that carries its own
 * copy and renders anywhere. The embedded form used to return the self-contained one, so a report
 * with twelve plots emitted twelve documents inside its own document. Browsers recover from that and
 * the plots did render, but the markup is invalid and every plot container ended up holding the
 * flattened head of its nested document.
 *
 * The fragment is only usable if the enclosing page runs Lets-Plot's own head bootstrap. A bare
 * `<script src="lets-plot.min.js">` is **not** enough: the per-plot scripts call
 * `window.letsPlotCall(...)`, which that bootstrap defines. Getting this wrong renders a page whose
 * plots are silently missing — text and captions present, blank space where the figures belong —
 * which is why the head content is asserted here rather than assumed.
 */
class PlotHtmlFormTest {

    @TempDir
    lateinit var tempDir: Path

    private fun samplePlot(): ScatterPlot =
        ScatterPlot(DoubleArray(20) { it.toDouble() }, DoubleArray(20) { it * 2.0 })

    private fun htmlReportWithPlots(count: Int): String {
        val doc = report("Plot Form Test") {
            heading("Plots", level = 2)
            repeat(count) { i ->
                paragraph("Text before plot $i.")
                plot(samplePlot(), caption = "Plot $i")
            }
        }
        val renderer = HtmlReportRenderer(RenderContext(outputDir = tempDir, plotDir = tempDir))
        doc.accept(renderer)
        return renderer.result()
    }

    private fun countOf(needle: String, haystack: String): Int =
        Regex(Regex.escape(needle), RegexOption.IGNORE_CASE).findAll(haystack).count()

    @Test
    @DisplayName("The embedded form is a fragment: no document structure, no library of its own")
    fun theEmbeddedFormIsAFragment() {
        val fragment = samplePlot().toEmbeddedHTML()
        for (tag in listOf("<html", "<head", "<body", "<!DOCTYPE")) {
            assertEquals(0, countOf(tag, fragment)) {
                "a fragment must not contain $tag; got:\n${fragment.take(400)}"
            }
        }
        assertEquals(0, countOf("src=\"https://cdn", fragment)) {
            "a fragment must not load the library itself; the enclosing page does that"
        }
        assertTrue(fragment.contains("<div")) { "a fragment should still carry its plot div" }
    }

    @Test
    @DisplayName("The self-contained form is a whole document that carries its own library")
    fun theSelfContainedFormIsAWholeDocument() {
        val document = samplePlot().toSelfContainedHTML()
        assertEquals(1, countOf("<html", document))
        assertEquals(1, countOf("</body>", document))
        assertTrue(document.contains("lets-plot")) {
            "the self-contained form must bring its own library, or it renders nowhere"
        }
    }

    /**
     * The defect in the form it was actually observed: counting document tags in a rendered report.
     * Twelve plots once produced thirteen `<html>` elements in one file.
     */
    @Test
    @DisplayName("A report with many plots is still one document")
    fun aMultiPlotReportIsOneDocument() {
        for (plots in listOf(1, 3, 6)) {
            val html = htmlReportWithPlots(plots)
            assertEquals(1, countOf("<html", html)) { "$plots plots produced more than one <html>" }
            assertEquals(1, countOf("</html>", html)) { "$plots plots produced more than one </html>" }
            assertEquals(1, countOf("</body>", html)) { "$plots plots produced more than one </body>" }
            assertEquals(1, countOf("<!DOCTYPE", html)) { "$plots plots produced more than one doctype" }
        }
    }

    /**
     * The half of the contract the fragment depends on. Without the bootstrap that defines
     * `letsPlotCall`, every plot in the report fails at run time with
     * "window.letsPlotCall is not a function" and the page renders with gaps where the figures
     * should be — no exception, no broken layout, just missing plots.
     */
    @Test
    @DisplayName("The report head carries the bootstrap the fragments call into")
    fun theReportHeadBootstrapsLetsPlot() {
        val html = htmlReportWithPlots(2)
        val head = html.substring(html.indexOf("<head>"), html.indexOf("</head>"))
        assertTrue(head.contains("letsPlotCall")) {
            "the head must define letsPlotCall; a bare script src for lets-plot.min.js does not, " +
                "and the per-plot fragments call it"
        }
    }

    /** Each plot container holds only its own content, not the flattened head of a nested document. */
    @Test
    @DisplayName("A plot container carries no stray document head content")
    fun plotContainersCarryNoStrayHeadContent() {
        val html = htmlReportWithPlots(3)
        // The nested documents contributed a <meta> and a <style> per plot when flattened.
        assertEquals(1, countOf("<meta charset", html)) {
            "more than one charset declaration means a nested document's head leaked into the page"
        }
    }
}
