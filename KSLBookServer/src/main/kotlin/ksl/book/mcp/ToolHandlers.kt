package ksl.book.mcp

/** Invalid tool input; the message is returned to the client as an isError result. */
class ToolInputException(message: String) : IllegalArgumentException(message)

/**
 * The six book tools. Handlers return markdown; every response ends with
 * citable URLs so students can be pointed at the published book.
 */
class ToolHandlers(
    private val store: BookStore,
    private val search: BookSearch,
) {
    /** Inline subsection content in get_section up to this total size. */
    private val inlineChildrenLimit = 20_000

    fun searchTextbook(query: String, maxResults: Int): String {
        if (query.isBlank()) throw ToolInputException("query must not be blank.")
        val hits = search.search(query, maxResults.coerceIn(1, 10))
        if (hits.isEmpty()) {
            return "No results for \"$query\". Try fewer or different terms; " +
                "list_chapters shows what the book covers."
        }
        return buildString {
            appendLine("Results for \"$query\":")
            appendLine()
            hits.forEachIndexed { i, hit ->
                val c = hit.chunk
                appendLine("${i + 1}. ${heading(c)} (id: ${c.id})")
                appendLine("   ${c.url}")
                appendLine("   ${hit.snippet}")
                appendLine()
            }
            append("Use get_section with a section number or id for the full content.")
        }
    }

    fun getSection(section: String): String {
        val chunk = store.find(section) ?: throw ToolInputException(unknownSection(section))
        val children = store.childrenOf(chunk)
        val inlineChildren =
            children.isNotEmpty() &&
                chunk.content.length + children.sumOf { it.content.length } <= inlineChildrenLimit
        return buildString {
            appendLine("Section: ${heading(chunk)}")
            chunk.chapter?.let { appendLine("Chapter: $it — ${chunk.chapterTitle}") }
            appendLine("URL: ${chunk.url}")
            appendLine()
            appendLine(chunk.content)
            if (inlineChildren) {
                children.forEach {
                    appendLine()
                    appendLine(it.content)
                }
            } else if (children.isNotEmpty()) {
                appendLine()
                appendLine("Subsections (fetch with get_section):")
                children.forEach { appendLine("- ${heading(it)} (id: ${it.id}) ${it.url}") }
            }
            appendLine()
            val prev = chunk.prevId?.let { store.byId[it] }
            val next = chunk.nextId?.let { store.byId[it] }
            prev?.let { appendLine("Previous: ${heading(it)} (id: ${it.id})") }
            next?.let { appendLine("Next: ${heading(it)} (id: ${it.id})") }
        }.trim()
    }

    fun getChapterOutline(chapter: String): String {
        val info = store.chapters.find { it.number == chapter.trim() }
            ?: throw ToolInputException(unknownChapter(chapter))
        val chunks = store.chapterChunks(info.number)
        return buildString {
            appendLine("Chapter ${info.number} — ${info.title}")
            appendLine("${info.sectionCount} sections, ${info.exerciseCount} exercises")
            appendLine()
            chunks.forEach { c ->
                val depth = (c.number?.count { it == '.' } ?: 0)
                val flags = listOfNotNull(
                    "code".takeIf { c.hasCode },
                    "exercises".takeIf { c.hasExercises },
                ).joinToString(", ")
                append("  ".repeat(depth))
                append("- ${heading(c)} (id: ${c.id})")
                if (flags.isNotEmpty()) append(" [$flags]")
                appendLine()
            }
            appendLine()
            append("Chapter URL: ${chunks.first().url}")
        }
    }

    fun listChapters(): String = buildString {
        appendLine("Chapters and appendices of the KSL textbook (https://rossetti.github.io/KSLBook/):")
        appendLine()
        store.chapters.forEach { ch ->
            val kind = if (ch.number.first().isDigit()) "Chapter" else "Appendix"
            append("- $kind ${ch.number}: ${ch.title} (${ch.sectionCount} sections")
            if (ch.exerciseCount > 0) append(", ${ch.exerciseCount} exercises")
            appendLine(")")
        }
        appendLine()
        append("Use get_chapter_outline for a chapter's sections, or search_textbook to find a topic.")
    }

    fun getExercises(chapter: String, exercise: String?): String {
        val ch = chapter.trim()
        val all = store.exercisesFor(ch)
        if (all.isEmpty()) {
            val withExercises = store.chapters.filter { it.exerciseCount > 0 }.map { it.number }
            throw ToolInputException(
                "No exercises found for chapter \"$chapter\". Chapters with exercises: $withExercises."
            )
        }
        val selected = if (exercise == null) all else {
            val number = if ('.' in exercise) exercise.trim() else "$ch.${exercise.trim()}"
            val match = all.filter { it.number == number }
            if (match.isEmpty()) throw ToolInputException(
                "No exercise \"$number\" in chapter $ch. Available: ${all.map { it.number }}."
            )
            match
        }
        return buildString {
            appendLine("Exercises for chapter $ch" + (exercise?.let { " (exercise ${selected.first().number})" } ?: "") + ":")
            selected.forEach { e ->
                appendLine()
                appendLine(e.content)
                appendLine()
                appendLine("URL: ${e.url}")
            }
        }.trim()
    }

    fun getRelatedSections(section: String): String {
        val chunk = store.find(section) ?: throw ToolInputException(unknownSection(section))
        val related = search.related(chunk)
        if (related.isEmpty()) return "No related sections found for ${heading(chunk)}."
        return buildString {
            appendLine("Sections related to ${heading(chunk)}:")
            appendLine()
            related.forEach { hit ->
                val c = hit.chunk
                appendLine("- ${heading(c)} (id: ${c.id})")
                appendLine("  ${c.url}")
                appendLine("  ${hit.snippet}")
            }
        }.trim()
    }

    private fun heading(c: BookChunk): String =
        listOfNotNull(c.number, c.title).joinToString(" ")

    private fun unknownSection(section: String) =
        "Unknown section \"$section\". Pass a section number (e.g. \"4.4.4\", \"A.2\") or a " +
            "section id (e.g. \"introDEDSPharmacy\"). Use list_chapters or get_chapter_outline to browse."

    private fun unknownChapter(chapter: String) =
        "Unknown chapter \"$chapter\". Valid chapters: ${store.chapters.map { it.number }}."
}
