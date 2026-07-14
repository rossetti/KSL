package ksl.book.gen

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import com.vladsch.flexmark.util.data.MutableDataSet
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * Converts one chunk's DOM subtree to markdown. Code blocks, math spans, and
 * figures are lifted out as placeholder tokens before flexmark runs (a generic
 * converter mangles highlighted code spans and escapes LaTeX), then substituted
 * back into the converted text.
 */
class HtmlToMarkdown(private val baseUrl: String) {

    private val converter = FlexmarkHtmlConverter.builder(
        MutableDataSet()
            .set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false)
            .set(FlexmarkHtmlConverter.OUTPUT_ATTRIBUTES_ID, false)
    ).build()

    fun convert(chunk: Element, pagePath: String): String {
        val el = chunk.clone()
        val stash = mutableListOf<String>()

        fun stash(md: String): String {
            stash += md
            return "KSLMDBLOCK${stash.size - 1}X"
        }

        // bookdown's self-link anchors on every heading
        el.select("a.anchor-section").forEach { it.remove() }

        // "**Exercise 4.1**text" needs a space after the label span
        el.select("span.exercise, span.example").forEach { it.after(TextNode(" ")) }

        // code blocks: wholeText flattens highlight spans; empty line anchors vanish
        el.select("pre").forEach { pre ->
            val code = pre.selectFirst("code")
            val text = (code ?: pre).wholeText().trimEnd()
            val lang = languageOf(pre, code)
            val target = pre.parent()?.takeIf { it.hasClass("sourceCode") } ?: pre
            target.replaceWith(Element("p").text(stash("```$lang\n$text\n```")))
        }

        // MathJax spans: keep the raw \( ... \) / \[ ... \] LaTeX untouched
        el.select("span.math").forEach { m ->
            m.replaceWith(TextNode(stash(m.wholeText())))
        }

        // figures: image with absolute URL so answers can link to the figure
        el.select("div.figure").forEach { fig ->
            val img = fig.selectFirst("img")
            val caption = fig.selectFirst("p.caption")?.text()?.trim()
                ?: img?.attr("alt")?.trim().orEmpty()
            val src = img?.attr("src").orEmpty()
            val md = if (src.isEmpty()) caption else "![$caption](${absolutize(src)})"
            fig.replaceWith(Element("p").text(stash(md)))
        }
        el.select("img[src]").forEach { it.attr("src", absolutize(it.attr("src"))) }

        el.select("a[href]").forEach { a ->
            a.attr("href", absolutizeHref(a.attr("href"), pagePath))
        }

        var md = converter.convert(el.outerHtml())
        for ((i, block) in stash.withIndex()) {
            md = md.replace("KSLMDBLOCK${i}X", block)
        }
        return md.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun languageOf(pre: Element, code: Element?): String {
        val classes = pre.classNames() + (code?.classNames() ?: emptySet())
        return when {
            "kotlin" in classes || "kt" in classes -> "kotlin"
            "r" in classes -> "r"
            "java" in classes -> "java"
            "bash" in classes || "sh" in classes -> "bash"
            "json" in classes -> "json"
            "xml" in classes -> "xml"
            else -> ""
        }
    }

    private fun absolutize(src: String): String =
        if (src.startsWith("http://") || src.startsWith("https://") || src.startsWith("data:")) src
        else "$baseUrl/${src.removePrefix("./")}"

    private fun absolutizeHref(href: String, pagePath: String): String = when {
        href.startsWith("http://") || href.startsWith("https://") ||
            href.startsWith("mailto:") || href.startsWith("data:") -> href
        href.startsWith("#") -> "$baseUrl/$pagePath$href"
        href.isEmpty() -> href
        else -> "$baseUrl/${href.removePrefix("./")}"
    }
}
