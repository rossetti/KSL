package ksl.book.search

import kotlinx.serialization.Serializable

/**
 * One retrievable unit of book content. Chunks are cut at level-3 section
 * boundaries; a level-2 section's own prose (before its first subsection)
 * is its own chunk. Front-matter chunks have null number/chapter.
 */
@Serializable
data class BookChunk(
    val id: String,
    val number: String?,
    val level: Int,
    val title: String,
    val chapter: String?,
    val chapterTitle: String?,
    val page: String,
    val url: String,
    val prevId: String?,
    val nextId: String?,
    val parentId: String?,
    val hasCode: Boolean,
    val hasMath: Boolean,
    val hasExercises: Boolean,
    val topics: List<String> = emptyList(),
    val content: String,
)

@Serializable
data class BookExercise(
    val id: String,
    val number: String,
    val chapter: String?,
    val page: String,
    val url: String,
    val content: String,
)
