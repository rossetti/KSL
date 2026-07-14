package ksl.book.gen

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlToMarkdownTest {

    private val md = HtmlToMarkdown("https://rossetti.github.io/KSLBook")

    private fun convert(html: String): String {
        val el = Jsoup.parse(html).selectFirst("div.section")!!
        return md.convert(el, "page.html")
    }

    @Test
    fun `kotlin code block is extracted byte-clean`() {
        val html = """
            <div id="s" class="section level3 hasAnchor" number="4.4.4">
            <h3><span class="header-section-number">4.4.4</span> Queueing<a href="p.html#s" class="anchor-section" aria-label="Anchor link to header"></a></h3>
            <div class="sourceCode" id="cb1"><pre class="sourceCode kt"><code class="sourceCode kotlin"><span id="cb1-1"><a href="p.html#cb1-1" tabindex="-1"></a><span class="kw">val</span> x <span class="op">=</span> <span class="dv">1</span> <span class="co">// one</span></span>
            <span id="cb1-2"><a href="p.html#cb1-2" tabindex="-1"></a>foo(<span class="st">"a &lt; b"</span>)</span></code></pre></div>
            </div>
        """.trimIndent()
        val out = convert(html)
        assertTrue(out.startsWith("### 4.4.4 Queueing"))
        assertTrue(out.contains("```kotlin\nval x = 1 // one\nfoo(\"a < b\")\n```"), out)
        assertFalse(out.contains("anchor"), out)
        assertFalse(out.contains("{#"), out)
    }

    @Test
    fun `math spans pass through as raw latex`() {
        val html = """
            <div id="m" class="section level3"><h3>Math</h3>
            <p>Utilization <span class="math inline">\(\rho = \lambda/(c\mu)\)</span> and
            <span class="math display">\[W_q = \frac{L_q}{\lambda}\]</span></p></div>
        """.trimIndent()
        val out = convert(html)
        assertTrue(out.contains("""\(\rho = \lambda/(c\mu)\)"""), out)
        assertTrue(out.contains("""\[W_q = \frac{L_q}{\lambda}\]"""), out)
    }

    @Test
    fun `figures become absolute image links`() {
        val html = """
            <div id="f" class="section level3"><h3>Fig</h3>
            <figure class="quarto-float quarto-float-fig figure">
            <div><img src="figures2/ch4/kslModeling.png" class="img-fluid figure-img" alt="Alt text"></div>
            <figcaption>Figure 4.5: KSL Packages</figcaption></figure></div>
        """.trimIndent()
        val out = convert(html)
        assertTrue(
            out.contains("![Figure 4.5: KSL Packages](https://rossetti.github.io/KSLBook/figures2/ch4/kslModeling.png)"),
            out
        )
    }

    @Test
    fun `exercise label keeps id out and space after bold`() {
        val html = """
            <div id="e" class="section level2"><h2>Exercises</h2>
            <div class="exercise">
            <p><span id="exr:ch4P1" class="exercise"><strong>Exercise 4.1  </strong></span>Draw the sample path.</p>
            </div></div>
        """.trimIndent()
        val out = convert(html)
        assertTrue(out.contains("**Exercise 4.1** Draw the sample path."), out)
    }

    @Test
    fun `relative links are absolutized`() {
        val html = """
            <div id="l" class="section level3"><h3>Links</h3>
            <p>See <a href="ch2rng.html#rngStreams">streams</a> and <a href="#local">local</a>.</p></div>
        """.trimIndent()
        val out = convert(html)
        assertTrue(out.contains("(https://rossetti.github.io/KSLBook/ch2rng.html#rngStreams)"), out)
        assertTrue(out.contains("(https://rossetti.github.io/KSLBook/page.html#local)"), out)
    }

    @Test
    fun `plain output blocks are plain fences`() {
        val html = """
            <div id="o" class="section level3"><h3>Out</h3>
            <pre><code>Average = 3.25
Count   = 12</code></pre></div>
        """.trimIndent()
        val out = convert(html)
        assertTrue(out.contains("```\nAverage = 3.25\nCount   = 12\n```"), out)
    }

    @Test
    fun `tables convert to pipe tables`() {
        val html = """
            <div id="t" class="section level3"><h3>T</h3>
            <table><tbody>
            <tr><td align="center">t</td><td align="center">0</td><td align="center">1</td></tr>
            <tr><td align="center">Y</td><td align="center">1</td><td align="center">2</td></tr>
            </tbody></table></div>
        """.trimIndent()
        val out = convert(html)
        assertTrue(out.contains("| t | 0 | 1 |"), out)
        assertTrue(out.contains("| Y | 1 | 2 |"), out)
    }

    @Test
    fun `heading number from span is preserved`() {
        val html = """
            <div id="h" class="section level2" number="11.2">
            <h2><span class="header-section-number">A.2</span> Random Numbers</h2><p>Body.</p></div>
        """.trimIndent()
        assertEquals("## A.2 Random Numbers\n\nBody.", convert(html))
    }
}
