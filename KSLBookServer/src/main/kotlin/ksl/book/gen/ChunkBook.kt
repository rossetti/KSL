package ksl.book.gen

import ksl.book.mcp.BookChunk
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

const val BASE_URL = "https://rossetti.github.io/KSLBook"

/**
 * Build-time content generator: parses the rendered bookdown HTML in docs/
 * into chunks.json + exercises.json, which get bundled into the server jar.
 *
 * Usage: ChunkBookKt <docsDir> <outputDir> [topicsFile]
 *
 * topicsFile is the curated {sectionId: [keywords]} sidecar; keywords are
 * merged into the chunks and indexed with a boost by the search. Ids that no
 * longer exist (after a book restructure) are reported, not fatal.
 */
fun main(args: Array<String>) {
    require(args.size in 2..3) { "usage: ChunkBookKt <docsDir> <outputDir> [topicsFile]" }
    val docsDir = File(args[0])
    val outDir = File(args[1], "book").apply { mkdirs() }
    // Graceful degradation: the rendered book lives in the git-ignored _book/ and
    // may be absent on a fresh clone or in CI. Emit empty content so the module
    // still builds; the real jar is produced locally after rendering into _book/.
    if (!File(docsDir, "index.html").isFile) {
        writeChunks(outDir, emptyList(), emptyList())
        println("WARN no index.html in $docsDir — book content skipped (empty chunks/exercises written)")
        return
    }
    val topics = args.getOrNull(2)?.let { loadTopics(File(it)) } ?: emptyMap()

    val toc = TocParser.parse(File(docsDir, "index.html"))
    val chapterTitles = toc.filter { it.level != null && '.' !in it.level!! }
        .associate { it.level!! to it.title }

    val chunker = PageChunker(docsDir, BASE_URL, HtmlToMarkdown(BASE_URL))
    val raw = mutableListOf<RawChunk>()
    val exercises = mutableListOf<ksl.book.mcp.BookExercise>()
    for (page in toc.map { it.path }.distinct()) {
        val (c, e) = chunker.process(page)
        raw += c
        exercises += e
    }

    // ids must be unique — they are the retrieval keys and URL anchors
    val dupes = raw.groupBy { it.id }.filterValues { it.size > 1 }.keys
    require(dupes.isEmpty()) { "duplicate chunk ids: $dupes" }

    val byNumber = raw.filter { it.number != null }.associateBy { it.number!! }
    val chunks = raw.mapIndexed { i, c ->
        val chapter = c.number?.substringBefore('.')
        BookChunk(
            id = c.id,
            number = c.number,
            level = c.level,
            title = c.title,
            chapter = chapter,
            chapterTitle = chapter?.let { chapterTitles[it] },
            page = c.page,
            url = "$BASE_URL/${c.page}#${c.id}",
            prevId = raw.getOrNull(i - 1)?.id,
            nextId = raw.getOrNull(i + 1)?.id,
            parentId = parentIdOf(c.number, byNumber),
            hasCode = c.hasCode,
            hasMath = c.hasMath,
            hasExercises = c.hasExercises,
            topics = topics[c.id] ?: emptyList(),
            content = c.content,
        )
    }

    val staleTopicIds = topics.keys - raw.map { it.id }.toSet()
    if (staleTopicIds.isNotEmpty()) {
        println("WARN topics.json has ${staleTopicIds.size} ids not in the book (regenerate the sidecar): $staleTopicIds")
    }

    writeChunks(outDir, chunks, exercises)

    report(chunks, exercises)
}

private fun loadTopics(file: File): Map<String, List<String>> {
    if (!file.isFile) return emptyMap()
    return Json.decodeFromString<Map<String, List<String>>>(file.readText())
}

private val outJson = Json { prettyPrint = true; encodeDefaults = true }

private fun writeChunks(
    outDir: File,
    chunks: List<BookChunk>,
    exercises: List<ksl.book.mcp.BookExercise>,
) {
    File(outDir, "chunks.json").writeText(
        outJson.encodeToString(ListSerializer(BookChunk.serializer()), chunks)
    )
    File(outDir, "exercises.json").writeText(
        outJson.encodeToString(ListSerializer(ksl.book.mcp.BookExercise.serializer()), exercises)
    )
}

private fun parentIdOf(number: String?, byNumber: Map<String, RawChunk>): String? {
    if (number == null || '.' !in number) return null
    var parent = number.substringBeforeLast('.')
    while (true) {
        byNumber[parent]?.let { return it.id }
        if ('.' !in parent) return null
        parent = parent.substringBeforeLast('.')
    }
}

private fun report(chunks: List<BookChunk>, exercises: List<ksl.book.mcp.BookExercise>) {
    if (chunks.isEmpty()) {
        println("chunks: 0 — no content parsed (check the parser matches the HTML in _book/)")
        return
    }
    val sizes = chunks.map { it.content.length }
    val empty = chunks.filter { it.content.length < 40 }
    val big = chunks.filter { it.content.length > 50_000 }
    println("chunks: ${chunks.size} (front matter: ${chunks.count { it.number == null }})")
    println("exercises: ${exercises.size} across ${exercises.mapNotNull { it.chapter }.distinct().size} chapters")
    println("chapters: ${chunks.mapNotNull { it.chapter }.distinct().sortedBy { it.padStart(3) }}")
    println("content bytes: total=${sizes.sum()} max=${sizes.max()} avg=${sizes.sum() / sizes.size}")
    println("topics: ${chunks.count { it.topics.isNotEmpty() }}/${chunks.size} chunks have keywords")
    if (empty.isNotEmpty()) println("WARN near-empty chunks: ${empty.map { it.id }}")
    if (big.isNotEmpty()) println("WARN oversized chunks: ${big.map { "${it.id}(${it.content.length})" }}")
}
