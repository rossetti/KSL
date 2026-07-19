package ksl.code.search.gen

import ksl.code.search.CodeDecl
import ksl.code.search.CodeMeta
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

const val GITHUB_REPO = "https://github.com/rossetti/KSL"

/** Modules whose `src/main/kotlin` is indexed. KSLCore is the API; KSLExamples supplies worked usage. */
val MODULES = listOf("KSLCore", "KSLExamples")

/**
 * Build-time content generator: parses the public declarations of the KSL source
 * modules into `code/chunks.json` + `code/meta.json`, bundled into the server jar.
 *
 * Usage: ExtractDeclarationsKt <kslRepoRoot> <outputDir> [topicsFile] [kslVersion]
 *
 * topicsFile is the curated {fqn: [keywords]} sidecar (like the book server's
 * topics.json); keywords are merged onto declarations and indexed with a boost.
 * Fqns that no longer exist are reported, never fatal.
 */
fun main(args: Array<String>) {
    require(args.size in 2..4) { "usage: ExtractDeclarationsKt <kslRepoRoot> <outputDir> [topicsFile] [kslVersion]" }
    val repoRoot = File(args[0])
    val outDir = File(args[1], "code").apply { mkdirs() }
    val topics = args.getOrNull(2)?.let { loadTopics(File(it)) } ?: emptyMap()
    val version = args.getOrNull(3)?.takeIf { it.isNotBlank() } ?: "develop"

    // 1. Parse every module's source into raw declarations.
    val rawByModule = LinkedHashMap<String, List<RawDecl>>()
    KotlinDeclarationParser().use { parser ->
        for (module in MODULES) {
            val srcRoot = File(repoRoot, "$module/src/main/kotlin")
            if (!srcRoot.isDirectory) {
                println("WARN module source not found, skipping: ${srcRoot.path}")
                continue
            }
            val decls = ArrayList<RawDecl>()
            srcRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { kt ->
                val rel = kt.relativeTo(repoRoot).path.replace('\\', '/')
                runCatching { parser.parse(kt.readText(), module, rel) }
                    .onSuccess { decls += it }
                    .onFailure { println("WARN failed to parse $rel: ${it.message}") }
            }
            rawByModule[module] = decls
            println("parsed $module: ${decls.size} declarations from ${srcRoot.walkTopDown().count { it.extension == "kt" }} files")
        }
    }
    val raw = rawByModule.values.flatten()
    require(raw.isNotEmpty()) { "no declarations extracted — check the KSL source path: ${repoRoot.path}" }

    // 2. Cross-reference: which KSLExamples files mention each KSLCore type by name.
    val exampleIndex = buildExampleIndex(rawByModule["KSLExamples"].orEmpty(), repoRoot)

    // 3. Assign stable, unique ids (overloaded functions share an fqn) and build chunks.
    val seen = HashMap<String, Int>()
    val chunks = raw
        .sortedWith(compareBy({ it.module }, { it.fqn }, { it.file }, { it.lineStart }, { it.signature }))
        .map { d ->
            // Example driver mains are all named "main" in a shared package — useless for display,
            // for search (the name^4 field carries no signal and every main looks alike in results),
            // and for stable ids (they collide on "<pkg>.main" and get order-dependent ~2/~3 suffixes).
            // Give each the file's base name as its identity (e.g. "Ch7Example1", "MCExamples") — the
            // name a student or agent refers to the example by, and a unique, stable fqn.
            val exampleName = if (d.module == "KSLExamples" && d.kind == "fun" && d.name == "main")
                d.file.substringAfterLast('/').removeSuffix(".kt") else null
            val name = exampleName ?: d.name
            val fqn = when {
                exampleName == null -> d.fqn
                d.pkg.isEmpty() -> exampleName
                else -> "${d.pkg}.$exampleName"
            }
            val base = "${d.module.lowercase()}-$fqn"
            val n = seen.merge(base, 1, Int::plus)!!
            val id = if (n == 1) base else "$base~$n"
            val isType = d.kind !in setOf("fun", "extension_fun", "type alias")
            CodeDecl(
                id = id,
                module = d.module,
                kind = d.kind,
                fqn = fqn,
                name = name,
                pkg = d.pkg,
                signature = d.signature,
                kdoc = d.kdoc?.ifBlank { null },
                supertypes = d.supertypes,
                members = d.members,
                topics = topics[d.fqn] ?: emptyList(),
                file = d.file,
                lineStart = d.lineStart,
                lineEnd = d.lineEnd,
                sourceUrl = "$GITHUB_REPO/blob/$version/${d.file}#L${d.lineStart}-L${d.lineEnd}",
                usedInExamples =
                    if (d.module == "KSLCore" && isType && d.name.length >= 4)
                        exampleIndex[d.name].orEmpty().sorted().take(25)
                    else emptyList(),
            )
        }

    // 4. ids are the retrieval keys — they must be unique.
    val dupes = chunks.groupBy { it.id }.filterValues { it.size > 1 }.keys
    require(dupes.isEmpty()) { "duplicate declaration ids: $dupes" }

    val moduleCounts = chunks.groupingBy { it.module }.eachCount()
    val meta = CodeMeta(
        kslVersion = version,
        buildDate = LocalDate.now().toString(),
        declarationCount = chunks.size,
        moduleCounts = moduleCounts,
        sourceUrl = "$GITHUB_REPO/tree/$version",
    )

    val json = Json { prettyPrint = true; encodeDefaults = true }
    File(outDir, "chunks.json").writeText(json.encodeToString(ListSerializer(CodeDecl.serializer()), chunks))
    File(outDir, "meta.json").writeText(json.encodeToString(CodeMeta.serializer(), meta))

    // 5. Report and flag stale topic keys.
    val staleTopics = topics.keys - raw.map { it.fqn }.toSet()
    if (staleTopics.isNotEmpty()) {
        println("WARN topics.json has ${staleTopics.size} fqns not in the source (regenerate the sidecar): ${staleTopics.take(20)}")
    }
    report(chunks, moduleCounts, version)
}

private fun loadTopics(file: File): Map<String, List<String>> {
    if (!file.isFile) return emptyMap()
    return runCatching { Json.decodeFromString<Map<String, List<String>>>(file.readText()) }
        .getOrElse { println("WARN could not parse ${file.path}: ${it.message}"); emptyMap() }
}

/**
 * Inverted index token -> example file paths. A KSLCore type is "used in" an
 * example when the example's source contains its simple name as an identifier.
 * A single tokenize pass over the example files keeps this near-instant.
 */
private fun buildExampleIndex(exampleDecls: List<RawDecl>, repoRoot: File): Map<String, Set<String>> {
    val root = File(repoRoot, "KSLExamples/src/main/kotlin")
    if (!root.isDirectory) return emptyMap()
    val ident = Regex("[A-Za-z_][A-Za-z0-9_]*")
    val index = HashMap<String, MutableSet<String>>()
    root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { kt ->
        val rel = kt.relativeTo(repoRoot).path.replace('\\', '/')
        ident.findAll(kt.readText()).mapTo(HashSet()) { it.value }.forEach { token ->
            index.getOrPut(token) { mutableSetOf() }.add(rel)
        }
    }
    return index
}

private fun report(chunks: List<CodeDecl>, moduleCounts: Map<String, Int>, version: String) {
    val kdoc = chunks.count { it.hasKdoc }
    val withExamples = chunks.count { it.usedInExamples.isNotEmpty() }
    val topics = chunks.count { it.topics.isNotEmpty() }
    val bytes = chunks.sumOf { it.signature.length + (it.kdoc?.length ?: 0) + it.members.sumOf { m -> m.length } }
    println("---")
    println("declarations: ${chunks.size}  (KDoc: $kdoc, cross-linked to examples: $withExamples, topic keywords: $topics)")
    println("by module: $moduleCounts")
    println("by kind: ${chunks.groupingBy { it.kind }.eachCount().toSortedMap()}")
    println("indexed content bytes: ~$bytes  (ksl ref: $version)")
    val noName = chunks.filter { it.name.isBlank() || it.fqn.isBlank() }
    if (noName.isNotEmpty()) println("WARN ${noName.size} declarations with blank name/fqn")
}
