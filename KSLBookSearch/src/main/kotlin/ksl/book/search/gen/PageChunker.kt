package ksl.book.search.gen

import ksl.book.search.BookExercise
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File

/**
 * A chunk before prev/next/parent wiring: content plus the metadata that can be
 * determined from its own page. [anchor] is the raw in-page element id used to
 * build the citation URL; [id] is the retrieval key, which ChunkBook may
 * disambiguate when Quarto's page-local ids (e.g. "exercises") collide across
 * pages. For most chunks id == anchor.
 */
data class RawChunk(
    val id: String,
    val anchor: String,
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
    fun process(entry: TocEntry): Pair<List<RawChunk>, List<BookExercise>> {
        val page = entry.path
        val doc = Jsoup.parse(File(docsDir, page), "UTF-8")
        val main = doc.selectFirst("main#quarto-document-content")
            ?: doc.selectFirst("main.content")
            ?: error("no <main> content root in $page")

        val chunks = mutableListOf<RawChunk>()

        // Front matter (index.html) wraps its content in a single level1 section;
        // numbered chapters/appendices place level2 sections directly under <main>,
        // with the chapter intro prose sitting between the title-block header and
        // the first section. The intro chunk is the container's own prose.
        val wrapper = main.selectFirst("> section.level1")
        val container = wrapper ?: main
        val own = container.clone()
        own.select("section").forEach { it.remove() }
        own.select("header#title-block-header").forEach { it.remove() }
        chunks += introChunk(main, container, own, entry)

        // chunk at level-3 granularity (so a level-3 subsection is retrievable by its
        // own number, e.g. get_section("4.4.1")): a level-2's own prose (before its
        // first subsection) is one chunk, then each level-3 is a chunk. A level-2 with
        // no level-3 children is a single chunk. A level-3 keeps its level-4 content
        // inside it unless that gets too large for retrieval, then split once more.
        main.select("section.level2").forEach { sec ->
            val level3s = sec.select("> section.level3")
            if (level3s.isEmpty()) {
                chunks += toChunk(sec, page)
            } else {
                val ownProse = sec.clone()
                ownProse.select("section").forEach { it.remove() }
                chunks += toChunk(ownProse, page)
                level3s.forEach { sub ->
                    val whole = toChunk(sub, page)
                    val level4s = sub.select("> section.level4")
                    if (whole.content.length <= MAX_CHUNK_CHARS || level4s.isEmpty()) {
                        chunks += whole
                    } else {
                        val subProse = sub.clone()
                        subProse.select("section").forEach { it.remove() }
                        chunks += toChunk(subProse, page)
                        level4s.forEach { chunks += toChunk(it, page) }
                    }
                }
            }
        }

        val exercises = main.select("div.theorem.exercise").mapNotNull { toExercise(it, page) }
        return chunks to exercises
    }

    /**
     * The chapter/page intro chunk. Its content is the container's own prose (all
     * sections and the title-block header already stripped). The anchor comes from
     * the chapter h1's identifier span or a front-matter wrapper section id (empty
     * for a bare page with no in-page anchor); the id falls back to the filename;
     * number and title come from the sidebar entry.
     */
    private fun introChunk(main: Element, container: Element, own: Element, entry: TocEntry): RawChunk {
        val anchor = main.selectFirst("header#title-block-header h1.title span.quarto-section-identifier[id]")
            ?.id()?.ifEmpty { null }
            ?: container.takeIf { it.tagName() == "section" }?.id()?.ifEmpty { null }
            ?: ""
        return RawChunk(
            id = anchor.ifEmpty { entry.path.substringBeforeLast(".html") },
            anchor = anchor,
            number = entry.level,
            level = 1,
            title = entry.title,
            page = entry.path,
            hasCode = own.selectFirst("pre.sourceCode") != null,
            hasMath = own.selectFirst("span.math") != null,
            hasExercises = own.selectFirst("div.exercise") != null,
            content = md.convert(own, entry.path),
        )
    }

    private fun toChunk(el: Element, page: String): RawChunk {
        val anchor = el.id()
        require(anchor.isNotEmpty()) { "section without id on $page" }
        // Quarto's data-number is the clean display number ("4.1", "A.2.1");
        // unnumbered (front-matter) sections have none.
        val number = el.attr("data-number").ifEmpty { null }
        val level = Regex("level(\\d)").find(el.className())?.groupValues?.get(1)?.toInt() ?: 1
        return RawChunk(
            id = anchor,
            anchor = anchor,
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
        clone.select("span.header-section-number, a.anchorjs-link").forEach { it.remove() }
        return clone.text().trim()
    }

    private fun toExercise(div: Element, page: String): BookExercise? {
        val id = div.id().ifEmpty { return null }
        val label = div.selectFirst("strong")?.text() ?: return null
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
