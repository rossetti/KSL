package ksl.book.gen

import ksl.book.mcp.BookExercise
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File

/**
 * A chunk before prev/next/parent wiring: content plus the metadata that can
 * be determined from its own page.
 */
data class RawChunk(
    val id: String,
    val number: String?,
    val level: Int,
    val title: String,
    val page: String,
    val hasCode: Boolean,
    val hasMath: Boolean,
    val hasExercises: Boolean,
    val content: String,
)

private const val MAX_CHUNK_CHARS = 50_000

class PageChunker(
    private val docsDir: File,
    private val baseUrl: String,
    private val md: HtmlToMarkdown,
) {

    /** Parses one page into chunks (level-3 granularity) and its exercises. */
    fun process(page: String): Pair<List<RawChunk>, List<BookExercise>> {
        val doc = Jsoup.parse(File(docsDir, page), "UTF-8")
        val top = doc.selectFirst(".page-inner section > div.section")
            ?: doc.selectFirst("div.section")
            ?: error("no section div found in $page")

        val chunks = mutableListOf<RawChunk>()

        // the section's own prose: everything except nested subsection divs
        val own = top.clone()
        own.select("div.section").forEach { if (it !== own) it.remove() }
        chunks += toChunk(own, page)

        // each level-3 subsection is one chunk; level-4 content stays inside it
        // unless the chunk gets too large for retrieval, then split once more
        top.select("div.section.level3").forEach { sub ->
            val whole = toChunk(sub, page)
            val level4s = sub.select("> div.section.level4")
            if (whole.content.length <= MAX_CHUNK_CHARS || level4s.isEmpty()) {
                chunks += whole
            } else {
                val ownProse = sub.clone()
                ownProse.select("div.section").forEach { if (it !== ownProse) it.remove() }
                chunks += toChunk(ownProse, page)
                level4s.forEach { chunks += toChunk(it, page) }
            }
        }

        val exercises = doc.select("div.exercise").mapNotNull { toExercise(it, page) }
        return chunks to exercises
    }

    private fun toChunk(el: Element, page: String): RawChunk {
        val id = el.id()
        require(id.isNotEmpty()) { "section div without id on $page" }
        // the heading span carries the display number ("A.2.1"); the div's
        // number attribute is bookdown's internal numeric one ("11.2.1")
        val number = el.selectFirst("> h1 > span.header-section-number, > h2 > span.header-section-number, > h3 > span.header-section-number, > h4 > span.header-section-number")
            ?.text()?.replace(Regex("^(Chapter|Appendix)\\s+"), "")?.trim()?.ifEmpty { null }
            ?: el.attr("number").ifEmpty { null }
        val level = Regex("level(\\d)").find(el.className())?.groupValues?.get(1)?.toInt() ?: 1
        return RawChunk(
            id = id,
            number = number,
            level = level,
            title = titleOf(el, page),
            page = page,
            hasCode = el.selectFirst("pre.sourceCode") != null,
            hasMath = el.selectFirst("span.math") != null,
            hasExercises = el.selectFirst("div.exercise") != null,
            content = md.convert(el, page),
        )
    }

    private fun titleOf(el: Element, page: String): String {
        val h = el.selectFirst("> h1, > h2, > h3, > h4, > h5") ?: return page
        val clone = h.clone()
        clone.select("span.header-section-number, a.anchor-section").forEach { it.remove() }
        return clone.text().trim()
    }

    private fun toExercise(div: Element, page: String): BookExercise? {
        val span = div.selectFirst("span[id^=exr:]") ?: return null
        val id = span.id()
        val label = span.selectFirst("strong")?.text() ?: return null
        val number = Regex("Exercise\\s+([A-Za-z0-9.]+)").find(label)?.groupValues?.get(1)
            ?: return null
        return BookExercise(
            id = id,
            number = number.trimEnd('.'),
            chapter = number.substringBefore('.').ifEmpty { null },
            page = page,
            url = "$baseUrl/$page#$id",
            content = md.convert(div, page),
        )
    }
}
