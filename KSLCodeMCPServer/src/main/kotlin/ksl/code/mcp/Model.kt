package ksl.code.mcp

import kotlinx.serialization.Serializable

/**
 * One retrievable unit of KSL source: a public declaration (class, interface,
 * object, enum, type alias, or top-level / extension function) with its KDoc,
 * signature, supertypes, and the member signatures that make up its API surface.
 *
 * Cut at build time from the KSL source by the Kotlin-PSI extractor and bundled
 * into the jar as `code/chunks.json`. Field names are the serialized keys.
 */
@Serializable
data class CodeDecl(
    /** Stable id and retrieval key, e.g. "kslcore-ksl.modeling.entity.Resource". */
    val id: String,
    /** Owning module: "KSLCore" or "KSLExamples". */
    val module: String,
    /** class, interface, object, companion object, enum class, annotation class,
     *  sealed class, abstract class, data class, fun, extension_fun, type alias. */
    val kind: String,
    /** Fully qualified name, e.g. "ksl.modeling.entity.Resource". */
    val fqn: String,
    /** Simple name, e.g. "Resource". */
    val name: String,
    /** Containing package, e.g. "ksl.modeling.entity". */
    val pkg: String,
    /** Reconstructed declaration signature (modifiers, type params, params, return type). */
    val signature: String,
    /** Raw KDoc markdown (including @param/@return/@throws), or null when undocumented. */
    val kdoc: String? = null,
    /** Declared supertypes (extends / implements), simple or qualified as written. */
    val supertypes: List<String> = emptyList(),
    /** Public/protected member signatures — the API surface (empty for functions). */
    val members: List<String> = emptyList(),
    /** Curated search keywords merged from topics.json (bridges student vocabulary). */
    val topics: List<String> = emptyList(),
    /** Source file path relative to the KSL repo root. */
    val file: String,
    val lineStart: Int,
    val lineEnd: Int,
    /** GitHub blob URL at the indexed KSL ref, with a line anchor. */
    val sourceUrl: String,
    /** KSLExamples files that reference this declaration's simple name (KSLCore decls only). */
    val usedInExamples: List<String> = emptyList(),
) {
    val hasKdoc: Boolean get() = !kdoc.isNullOrBlank()
}

/**
 * Build metadata written alongside the chunks so the running server can report
 * exactly what it was built from (get_server_info) without a live library.
 */
@Serializable
data class CodeMeta(
    val kslVersion: String,
    val buildDate: String,
    val declarationCount: Int,
    /** Declaration count per module, e.g. {"KSLCore": 1800, "KSLExamples": 300}. */
    val moduleCounts: Map<String, Int>,
    /** GitHub tree URL at the indexed ref (points at the source the index was built from). */
    val sourceUrl: String,
)
