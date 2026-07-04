package ksl.code.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.lucene.analysis.CharArraySet
import org.apache.lucene.analysis.en.EnglishAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.ByteBuffersDirectory

private val logger = KotlinLogging.logger {}

data class SearchHit(val decl: CodeDecl, val score: Float)

/**
 * BM25 search over the KSL declarations. The index lives in memory and is built
 * lazily on first use (a few thousand declarations; well under a second). Only
 * the id and module are stored in Lucene; the full declaration is looked up from
 * the store by id.
 */
class CodeSearch(private val store: CodeStore) {

    // question/instruction words match declaration names/KDoc poorly and otherwise
    // dominate scoring; drop them on top of the English stop set (same as the book server)
    private val analyzer = EnglishAnalyzer(
        CharArraySet.copy(EnglishAnalyzer.getDefaultStopSet()).apply {
            addAll(
                listOf(
                    "what", "how", "why", "when", "where", "which", "who", "whom",
                    "does", "do", "did", "can", "could", "should", "would",
                    "explain", "show", "tell", "find", "me", "i", "you", "we", "my", "about",
                    "class", "function", "method", "ksl", "kotlin", "use", "using", "example",
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

    fun search(query: String, maxResults: Int, module: String? = null): List<SearchHit> {
        val s = ensureIndex()
        val parser = MultiFieldQueryParser(
            arrayOf("name", "fqn", "topics", "signature", "kdoc", "members", "content"),
            analyzer,
            mapOf(
                "name" to 4.0f, "fqn" to 3.0f, "topics" to 2.5f,
                "signature" to 1.5f, "kdoc" to 1.0f, "members" to 1.0f, "content" to 1.0f,
            ),
        )
        // students paste queries with ?, :, and stray AND/OR; the fallback escapes and lowercases
        val parsed = try {
            parser.parse(query)
        } catch (_: Exception) {
            parser.parse(QueryParser.escape(query.lowercase()))
        }
        val finalQuery = if (module != null) {
            BooleanQuery.Builder()
                .add(parsed, BooleanClause.Occur.MUST)
                .add(TermQuery(Term("module", module.trim().lowercase())), BooleanClause.Occur.FILTER)
                .build()
        } else parsed
        val ids = s.storedFields()
        return s.search(finalQuery, maxResults).scoreDocs.mapNotNull { sd ->
            store.byId[ids.document(sd.doc).get("id")]?.let { SearchHit(it, sd.score) }
        }
    }

    fun totalDocuments(): Int = ensureIndex().indexReader.numDocs()

    private fun ensureIndex(): IndexSearcher {
        searcher?.let { return it }
        synchronized(this) {
            searcher?.let { return it }
            val start = System.currentTimeMillis()
            IndexWriter(directory, IndexWriterConfig(analyzer)).use { writer ->
                for (d in store.decls) {
                    writer.addDocument(Document().apply {
                        add(StringField("id", d.id, Field.Store.YES))
                        add(StringField("module", d.module.lowercase(), Field.Store.NO))
                        add(TextField("name", splitIdentifier(d.name), Field.Store.NO))
                        add(TextField("fqn", d.fqn, Field.Store.NO))
                        add(TextField("topics", d.topics.joinToString(" "), Field.Store.NO))
                        add(TextField("signature", d.signature, Field.Store.NO))
                        add(TextField("kdoc", d.kdoc.orEmpty(), Field.Store.NO))
                        add(TextField("members", d.members.joinToString(" ") { splitIdentifier(it) }, Field.Store.NO))
                        add(TextField("content", content(d), Field.Store.NO))
                    })
                }
            }
            val s = IndexSearcher(DirectoryReader.open(directory))
            logger.info { "indexed ${store.decls.size} declarations in ${System.currentTimeMillis() - start} ms" }
            searcher = s
            return s
        }
    }

    private fun content(d: CodeDecl): String = buildString {
        append(splitIdentifier(d.name)); append(' ')
        append(d.fqn); append(' ')
        append(d.signature); append(' ')
        append(d.kdoc.orEmpty()); append(' ')
        d.supertypes.forEach { append(splitIdentifier(it)); append(' ') }
        d.topics.forEach { append(it); append(' ') }
    }
}

/**
 * Splits camelCase / PascalCase identifiers so "ProcessModel" also matches the
 * terms "process" and "model", while keeping the original token. Bridges the gap
 * between prose queries and code names.
 */
internal fun splitIdentifier(id: String): String {
    val parts = Regex("[A-Z]+(?![a-z])|[A-Z][a-z0-9]*|[a-z0-9]+").findAll(id).map { it.value }.toList()
    return (listOf(id) + parts).joinToString(" ")
}
