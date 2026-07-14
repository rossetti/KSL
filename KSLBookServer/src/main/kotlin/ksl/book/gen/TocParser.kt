package ksl.book.gen

import org.jsoup.Jsoup
import java.io.File

/**
 * A sidebar TOC entry from index.html. [level] is the section number string
 * ("4", "4.4", "A.2") or null for front-matter pages.
 */
data class TocEntry(val level: String?, val path: String, val title: String)

object TocParser {

    /**
     * Parses the gitbook sidebar TOC (`li.chapter` entries) from index.html.
     * This is the canonical ordered list of all pages; filenames must never
     * be derived from titles.
     */
    fun parse(indexHtml: File): List<TocEntry> {
        val doc = Jsoup.parse(indexHtml, "UTF-8")
        return doc.select("li.chapter").map { li ->
            val level = li.attr("data-level").ifEmpty { null }
            val path = li.attr("data-path")
            require(path.isNotEmpty()) { "li.chapter without data-path: ${li.outerHtml().take(120)}" }
            val anchor = li.selectFirst("> a")
            val title = if (anchor != null) {
                val a = anchor.clone()
                a.select("b").forEach { it.remove() } // leading section number
                a.text().trim()
            } else {
                li.ownText().trim()
            }
            TocEntry(level, path, title)
        }
    }
}
