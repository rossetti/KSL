package ksl.book.search

/**
 * Dev harness: query the bundled index from the command line.
 *
 * ./gradlew searchBook -Pq="pharmacy model event scheduling"
 * ./gradlew searchBook -Pq="related 4.4.4"
 */
fun main(args: Array<String>) {
    val store = BookStore.instance
    val search = BookSearch(store)
    val hits = if (args.firstOrNull() == "related") {
        val section = args.getOrNull(1) ?: "4.4.4"
        val chunk = store.find(section) ?: error("unknown section $section")
        println("related to: ${chunk.number} ${chunk.title}")
        search.related(chunk)
    } else {
        val query = args.joinToString(" ").ifBlank { "pharmacy model" }
        println("query: $query")
        search.search(query, 8)
    }
    for (hit in hits) {
        val c = hit.chunk
        println("%6.2f  %-8s %-55s %s".format(hit.score, c.number ?: "-", c.title.take(55), c.id))
        println("        ${hit.snippet.take(160)}")
    }
}
