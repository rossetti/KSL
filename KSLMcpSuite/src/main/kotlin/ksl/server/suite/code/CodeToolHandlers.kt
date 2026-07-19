/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.server.suite.code

import ksl.code.search.CodeDecl
import ksl.code.search.CodeSearch
import ksl.code.search.CodeStore
import ksl.server.suite.SuiteBuildInfo

/** Invalid tool input; the message is returned to the client as an isError result. */
class ToolInputException(message: String) : IllegalArgumentException(message)

/**
 * The eight code tools. Handlers return markdown; responses cite the GitHub source URL so students
 * can be pointed at the actual KSL code they are asking about. Nothing here calls the network —
 * everything is served from the bundled index. Copied from the standalone KSLCodeMCPServer's
 * ToolHandlers, retargeted onto the KSLCodeSearch library; the reported server version comes from
 * the suite's BuildInfo.
 */
class CodeToolHandlers(
    private val store: CodeStore,
    private val search: CodeSearch,
) {
    private val maxMembersInline = 80

    fun searchCode(query: String, maxResults: Int, module: String?): String {
        if (query.isBlank()) throw ToolInputException("query must not be blank.")
        module?.let { validateModule(it) }
        val hits = search.search(query, maxResults.coerceIn(1, 15), module)
        if (hits.isEmpty()) {
            return "No results for \"$query\"" + (module?.let { " in module $it" } ?: "") + ". " +
                "Try fewer or different terms, or list_modules to see what is indexed."
        }
        return buildString {
            appendLine("Results for \"$query\"" + (module?.let { " (module $it)" } ?: "") + ":")
            appendLine()
            hits.forEachIndexed { i, hit ->
                val d = hit.decl
                appendLine("${i + 1}. ${d.kind} ${d.fqn}  [${d.module}]")
                appendLine("   ${d.signature}")
                d.kdoc?.let { appendLine("   ${firstLine(it)}") }
                appendLine("   ${d.sourceUrl}")
                appendLine()
            }
            append("Use get_class with an fqn for the full API, or get_example to see it used.")
        }.trim()
    }

    fun getClass(ref: String): String {
        val matches = store.resolve(ref)
        if (matches.isEmpty()) throw ToolInputException(unknownDecl(ref))
        // For an fqn with overloads (or an ambiguous simple name) show each match.
        return matches.take(6).joinToString("\n\n${"-".repeat(60)}\n\n") { renderDecl(it) } +
            if (matches.size > 6) "\n\n(+${matches.size - 6} more matches; qualify with the full fqn to narrow.)" else ""
    }

    fun getExample(ref: String): String {
        val matches = store.resolve(ref)
        if (matches.isEmpty()) throw ToolInputException(unknownDecl(ref))
        val decl = matches.firstOrNull { it.usedInExamples.isNotEmpty() } ?: matches.first()
        if (decl.usedInExamples.isEmpty()) {
            return "No KSLExamples files reference ${decl.name} directly. " +
                "Try get_related_examples with a topic (e.g. \"${decl.name.lowercase()}\"), " +
                "or search_code with module=\"KSLExamples\"."
        }
        return buildString {
            appendLine("Examples using ${decl.kind} ${decl.fqn}:")
            appendLine()
            decl.usedInExamples.forEach { path ->
                appendLine("- $path")
                appendLine("  ${blobUrl(path)}")
            }
            appendLine()
            append("Open a file's URL to read the full worked example, or get_class ${decl.fqn} for its API.")
        }.trim()
    }

    fun getPackageOverview(pkg: String): String {
        val decls = store.declsInPackage(pkg)
        if (decls.isEmpty()) {
            val near = store.packages.filter { it.contains(pkg.trim(), ignoreCase = true) }.take(10)
            throw ToolInputException(
                "No declarations in package \"$pkg\"." +
                    if (near.isNotEmpty()) " Did you mean: ${near.joinToString(", ")}?" else " Use list_modules to see all packages."
            )
        }
        val module = decls.first().module
        return buildString {
            appendLine("Package $pkg  [$module] — ${decls.size} public declarations:")
            appendLine()
            decls.groupBy { it.kind }.toSortedMap().forEach { (kind, group) ->
                appendLine("$kind:")
                group.forEach { d ->
                    append("  - ${d.name}")
                    d.kdoc?.let { append(" — ${firstLine(it)}") }
                    appendLine()
                }
            }
            appendLine()
            append("Use get_class with an fqn (e.g. ${decls.first().fqn}) for full details.")
        }.trim()
    }

    fun findSubclasses(ref: String): String {
        val target = store.resolve(ref).firstOrNull()
        val name = target?.name ?: ref.substringAfterLast('.').trim()
        val subs = store.subtypesOf(name)
        if (subs.isEmpty()) {
            return "No indexed declarations extend or implement \"$name\". " +
                "(Only public declarations in ${store.modules.joinToString(", ") { it.name }} are indexed.)"
        }
        return buildString {
            appendLine("Declarations that extend or implement $name (${subs.size}):")
            appendLine()
            subs.forEach { d ->
                appendLine("- ${d.kind} ${d.fqn}  [${d.module}]")
                appendLine("  supertypes: ${d.supertypes.joinToString(", ")}")
                appendLine("  ${d.sourceUrl}")
            }
        }.trim()
    }

    fun getRelatedExamples(topic: String): String {
        if (topic.isBlank()) throw ToolInputException("topic must not be blank.")
        // Search the examples module, then surface the example files behind the hits.
        val hits = search.search(topic, 10, module = "KSLExamples")
        val files = LinkedHashMap<String, CodeDecl>()
        hits.forEach { files.putIfAbsent(it.decl.file, it.decl) }
        // Also include KSLCore hits' cross-referenced example files.
        if (files.size < 5) {
            search.search(topic, 6).forEach { hit ->
                hit.decl.usedInExamples.forEach { path -> files.putIfAbsent(path, hit.decl) }
            }
        }
        if (files.isEmpty()) {
            return "No examples found related to \"$topic\". Try a broader topic or search_code."
        }
        return buildString {
            appendLine("KSLExamples related to \"$topic\":")
            appendLine()
            files.entries.take(10).forEach { (path, d) ->
                appendLine("- $path")
                appendLine("  ${blobUrl(path)}")
            }
            appendLine()
            append("Open a file URL to read it, or get_class for the API it demonstrates.")
        }.trim()
    }

    fun listModules(): String = buildString {
        appendLine("KSL modules indexed by this server (${store.meta.declarationCount} declarations, KSL ${store.meta.kslVersion}):")
        appendLine()
        store.modules.forEach { m ->
            appendLine("- ${m.name}: ${m.declarationCount} declarations, ${m.packages.size} packages")
        }
        appendLine()
        appendLine("Packages:")
        store.modules.forEach { m ->
            appendLine("  [${m.name}]")
            m.packages.forEach { appendLine("    - $it") }
        }
        appendLine()
        append("Use get_package_overview with a package name, or search_code for a topic.")
    }.trim()

    fun getServerInfo(): String = buildString {
        appendLine("KSL Code MCP server")
        appendLine("  server version: ${SuiteBuildInfo.version}")
        appendLine("  KSL ref:        ${store.meta.kslVersion}")
        appendLine("  index built:    ${store.meta.buildDate}")
        appendLine("  declarations:   ${store.meta.declarationCount}")
        appendLine("  by module:      ${store.meta.moduleCounts}")
        appendLine("  source:         ${store.meta.sourceUrl}")
        append("This server answers from KSL ${store.meta.kslVersion}; re-index if the library has moved on.")
    }.trim()

    // ---- rendering helpers ----

    private fun renderDecl(d: CodeDecl): String = buildString {
        appendLine("${d.kind} ${d.fqn}  [${d.module}]")
        appendLine("Signature: ${d.signature}")
        if (d.supertypes.isNotEmpty()) appendLine("Supertypes: ${d.supertypes.joinToString(", ")}")
        appendLine("Source: ${d.sourceUrl}")
        appendLine()
        if (d.kdoc != null) {
            appendLine(d.kdoc)
        } else {
            appendLine("(no KDoc)")
        }
        if (d.members.isNotEmpty()) {
            appendLine()
            appendLine("API surface (${d.members.size} public members):")
            d.members.take(maxMembersInline).forEach { appendLine("  - $it") }
            if (d.members.size > maxMembersInline) appendLine("  … (+${d.members.size - maxMembersInline} more; see the source)")
        }
        if (d.usedInExamples.isNotEmpty()) {
            appendLine()
            appendLine("Used in examples (get_example ${d.fqn} for links):")
            d.usedInExamples.take(8).forEach { appendLine("  - $it") }
        }
    }.trim()

    private fun validateModule(module: String) {
        val names = store.modules.map { it.name }
        if (names.none { it.equals(module.trim(), ignoreCase = true) }) {
            throw ToolInputException("Unknown module \"$module\". Valid modules: ${names.joinToString(", ")}.")
        }
    }

    private fun firstLine(kdoc: String): String {
        val line = kdoc.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("@") } ?: ""
        return if (line.length > 200) line.take(197) + "…" else line
    }

    private fun blobUrl(repoRelPath: String): String =
        "${store.meta.sourceUrl.substringBefore("/tree/")}/blob/${store.meta.kslVersion}/$repoRelPath"

    private fun unknownDecl(ref: String) =
        "Unknown declaration \"$ref\". Pass a fully-qualified name (e.g. " +
            "\"ksl.modeling.entity.Resource\"), a simple name (e.g. \"Resource\"), or an id from " +
            "search_code. Use search_code or list_modules to browse."
}
