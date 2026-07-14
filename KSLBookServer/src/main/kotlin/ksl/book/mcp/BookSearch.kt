package ksl.book.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.lucene.analysis.CharArraySet
import org.apache.lucene.analysis.en.EnglishAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.queries.mlt.MoreLikeThis
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.store.ByteBuffersDirectory
import java.io.StringReader

private val logger = KotlinLogging.logger {}

data class SearchHit(val chunk: BookChunk, val score: Float, val snippet: String)

/**
 * BM25 search over the book chunks. The index lives in memory and is built
 * lazily on first use (the corpus is ~2 MB; this takes well under a second).
 * Full chunk text is looked up from the store by id; only ids are stored.
 */
class BookSearch(private val store: BookStore) {

    // question words aren't in Lucene's English stop set, and they otherwise match
    // question-style section titles ("What are Entities?") with the title boost
    private val analyzer = EnglishAnalyzer(
        CharArraySet.copy(EnglishAnalyzer.getDefaultStopSet()).apply {
            addAll(
                listOf(
                    "what", "how", "why", "when", "where", "which", "who", "whom",
                    "does", "do", "did", "can", "could", "should", "would",
                    "explain", "show", "tell", "find", "me", "i", "you", "we", "my", "about",
                )
            )
        }
    )
    private val directory = ByteBuffersDirectory()

    @Volatile
    private var searcher: IndexSearcher? = null

    /** Builds the index now instead of on the first search; failures surface at startup. */
    fun warmUp() {
        ensureIndex()
    }

    fun search(query: String, maxResults: Int): List<SearchHit> {
        val s = ensureIndex()
        val parser = MultiFieldQueryParser(
            arrayOf("title", "topics", "content"),
            analyzer,
            mapOf("title" to 3.0f, "topics" to 2.0f, "content" to 1.0f),
        )
        // students type queries with ?, :, and stray AND/OR in them; lowercasing
        // in the fallback demotes boolean operators to plain terms
        val parsed = try {
            parser.parse(query)
        } catch (_: Exception) {
            parser.parse(QueryParser.escape(query.lowercase()))
        }
        val ids = s.storedFields()
        return s.search(parsed, maxResults).scoreDocs.mapNotNull { sd ->
            val chunk = store.byId[ids.document(sd.doc).get("id")] ?: return@mapNotNull null
            SearchHit(chunk, sd.score, snippet(chunk.content, query))
        }
    }

    /** Content-similar sections, excluding the section itself and its own family. */
    fun related(chunk: BookChunk, maxResults: Int = 5): List<SearchHit> {
        val s = ensureIndex()
        val mlt = MoreLikeThis(s.indexReader).apply {
            analyzer = this@BookSearch.analyzer
            fieldNames = arrayOf("title", "content")
            minTermFreq = 1
            minDocFreq = 2
        }
        val query = mlt.like("content", StringReader("${chunk.title}\n${chunk.content}"))
        val family = setOf(chunk.id, chunk.parentId) +
            store.childrenOf(chunk).map { it.id } +
            store.chunks.filter { it.parentId != null && it.parentId == chunk.parentId }.map { it.id }
        val ids = s.storedFields()
        return s.search(query, maxResults + family.size).scoreDocs
            .mapNotNull { sd -> store.byId[ids.document(sd.doc).get("id")] }
            .filter { it.id !in family }
            .take(maxResults)
            .map { SearchHit(it, 0f, firstSentence(it.content)) }
    }

    private fun ensureIndex(): IndexSearcher {
        searcher?.let { return it }
        synchronized(this) {
            searcher?.let { return it }
            val start = System.currentTimeMillis()
            IndexWriter(directory, IndexWriterConfig(analyzer)).use { writer ->
                for (c in store.chunks) {
                    writer.addDocument(Document().apply {
                        add(StringField("id", c.id, Field.Store.YES))
                        add(TextField("title", "${c.number.orEmpty()} ${c.title}", Field.Store.NO))
                        add(TextField("topics", c.topics.joinToString(" "), Field.Store.NO))
                        add(TextField("content", c.content, Field.Store.NO))
                    })
                }
            }
            val s = IndexSearcher(DirectoryReader.open(directory))
            logger.info { "indexed ${store.chunks.size} chunks in ${System.currentTimeMillis() - start} ms" }
            searcher = s
            return s
        }
    }

    /** ±200-char window around the first query-term match, cut at word boundaries. */
    private fun snippet(content: String, query: String): String {
        val text = content.replace(Regex("\\s+"), " ")
        val lower = text.lowercase()
        val hit = analyzeTerms(query).asSequence()
            .map { lower.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: 0
        var start = (hit - 200).coerceAtLeast(0)
        var end = (hit + 200).coerceAtMost(text.length)
        if (start > 0) start = text.indexOf(' ', start).let { if (it in 1 until hit) it + 1 else start }
        if (end < text.length) end = text.lastIndexOf(' ', end).let { if (it > hit) it else end }
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + text.substring(start, end).trim() + suffix
    }

    private fun firstSentence(content: String): String {
        val body = content.lineSequence()
            .dropWhile { it.isBlank() || it.startsWith("#") }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val cut = body.indexOf(". ").let { if (it in 40..300) it + 1 else minOf(200, body.length) }
        val s = body.take(cut).trim()
        return s + if (cut < body.length && !s.endsWith(".")) "…" else ""
    }

    private fun analyzeTerms(query: String): List<String> =
        analyzer.tokenStream("content", query).use { ts ->
            val attr = ts.addAttribute(CharTermAttribute::class.java)
            ts.reset()
            buildList {
                while (ts.incrementToken()) add(attr.toString())
                ts.end()
            }
        }
}
