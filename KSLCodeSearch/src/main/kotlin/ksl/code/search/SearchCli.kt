package ksl.code.search

/**
 * Dev harness: query the bundled index from the command line.
 *
 *   ./gradlew searchCode -Pq="seize release resource"
 *   ./gradlew searchCode -Pq="subclasses ModelElement"
 */
fun main(args: Array<String>) {
    val store = CodeStore.instance
    val search = CodeSearch(store)
    when (args.firstOrNull()) {
        "subclasses" -> {
            val type = args.getOrNull(1) ?: "ModelElement"
            println("subclasses of $type:")
            store.subtypesOf(type).forEach { println("  ${it.kind.padEnd(16)} ${it.fqn}") }
        }
        else -> {
            val query = args.joinToString(" ").ifBlank { "resource seize release" }
            println("query: $query")
            search.search(query, 10).forEach { hit ->
                val d = hit.decl
                println("%6.2f  %-16s %-50s %s".format(hit.score, d.kind, d.name.take(50), d.module))
                println("        ${d.signature.take(140)}")
            }
        }
    }
}
