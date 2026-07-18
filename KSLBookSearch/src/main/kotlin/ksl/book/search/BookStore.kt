package ksl.book.search

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

data class ChapterInfo(
    val number: String,
    val title: String,
    val sectionCount: Int,
    val exerciseCount: Int,
)

/**
 * In-memory view of the bundled book content. Loaded once from the
 * chunks.json / exercises.json resources generated at build time.
 */
class BookStore(
    val chunks: List<BookChunk>,
    val exercises: List<BookExercise>,
) {
    val byId: Map<String, BookChunk> = chunks.associateBy { it.id }
    val byNumber: Map<String, BookChunk> =
        chunks.filter { it.number != null }.associateBy { it.number!! }

    /** Chapters and appendices in book order. */
    val chapters: List<ChapterInfo> =
        chunks.mapNotNull { it.chapter }.distinct().map { ch ->
            ChapterInfo(
                number = ch,
                title = chunks.first { it.chapter == ch }.chapterTitle ?: ch,
                sectionCount = chunks.count { it.chapter == ch },
                exerciseCount = exercises.count { it.chapter == ch },
            )
        }

    /** Looks up a section by number ("4.4.4", "A.2") or by anchor id. */
    fun find(section: String): BookChunk? = byNumber[section.trim()] ?: byId[section.trim()]

    fun childrenOf(chunk: BookChunk): List<BookChunk> =
        chunks.filter { it.parentId == chunk.id }

    fun chapterChunks(chapter: String): List<BookChunk> =
        chunks.filter { it.chapter == chapter.trim() }

    fun exercisesFor(chapter: String): List<BookExercise> =
        exercises.filter { it.chapter == chapter.trim() }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        val instance: BookStore by lazy { load() }

        fun load(): BookStore {
            fun resource(name: String): String =
                BookStore::class.java.getResourceAsStream(name)
                    ?.bufferedReader()?.readText()
                    ?: error("missing bundled resource $name")
            return BookStore(
                chunks = json.decodeFromString(
                    ListSerializer(BookChunk.serializer()), resource("/book/chunks.json")
                ),
                exercises = json.decodeFromString(
                    ListSerializer(BookExercise.serializer()), resource("/book/exercises.json")
                ),
            )
        }
    }
}
