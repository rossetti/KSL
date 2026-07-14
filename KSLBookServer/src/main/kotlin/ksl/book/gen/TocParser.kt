package ksl.book.gen

import org.jsoup.Jsoup
import java.io.File

/**
 * A sidebar TOC entry from index.html. [level] is the chapter number/letter
 * ("4", "A") or null for front-matter pages (Preface, About the Author,
 * References). Filenames come only from the sidebar, never derived from titles.
 */
data class TocEntry(val level: String?, val path: String, val title: String)

object TocParser {

    /**
     * Parses the Quarto sidebar (nav#quarto-sidebar) from index.html into the
     * canonical ordered list of pages. Chapter/appendix links carry a
     * chapter-number and chapter-title span; front-matter links carry only a
     * menu-text span. This is the single source of page order and filenames.
     */
    fun parse(indexHtml: File): List<TocEntry> {
        val doc = Jsoup.parse(indexHtml, "UTF-8")
        return doc.select("nav#quarto-sidebar a.sidebar-link[href\$=.html]").map { a ->
            val path = a.attr("href").removePrefix("./")
            require(path.isNotEmpty()) { "sidebar link without href: ${a.outerHtml().take(120)}" }
            val level = a.selectFirst("span.chapter-number")?.text()?.trim()?.ifEmpty { null }
            val title = a.selectFirst("span.chapter-title")?.text()?.trim()
                ?: a.selectFirst("span.menu-text")?.text()?.trim()
                ?: a.text().trim()
            TocEntry(level, path, title)
        }
    }
}
