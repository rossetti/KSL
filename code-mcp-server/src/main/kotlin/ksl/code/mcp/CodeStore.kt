package ksl.code.mcp

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

data class ModuleInfo(
    val name: String,
    val declarationCount: Int,
    val packages: List<String>,
)

/**
 * In-memory view of the bundled KSL declarations. Loaded once from the
 * chunks.json / meta.json resources generated at build time from the KSL source.
 */
class CodeStore(
    val decls: List<CodeDecl>,
    val meta: CodeMeta,
) {
    val byId: Map<String, CodeDecl> = decls.associateBy { it.id }

    /** Fqn -> declarations. A list because overloaded functions share an fqn. */
    val byFqn: Map<String, List<CodeDecl>> = decls.groupBy { it.fqn }

    /** Simple name -> declarations, for lenient lookup when the student omits the package. */
    private val byName: Map<String, List<CodeDecl>> = decls.groupBy { it.name }

    /** Modules in the order they were indexed, with their package lists. */
    val modules: List<ModuleInfo> =
        decls.map { it.module }.distinct().map { m ->
            ModuleInfo(
                name = m,
                declarationCount = decls.count { it.module == m },
                packages = decls.filter { it.module == m }.map { it.pkg }.distinct().sorted(),
            )
        }

    fun declsInPackage(pkg: String): List<CodeDecl> =
        decls.filter { it.pkg == pkg.trim() }.sortedBy { it.name }

    val packages: List<String> = decls.map { it.pkg }.distinct().sorted()

    /**
     * Resolve a declaration reference: exact id, then exact fqn (all overloads),
     * then simple name (all matches). Returns every declaration that matches so
     * callers can report overloads or ambiguity.
     */
    fun resolve(ref: String): List<CodeDecl> {
        val r = ref.trim()
        byId[r]?.let { return listOf(it) }
        byFqn[r]?.let { if (it.isNotEmpty()) return it }
        byName[r]?.let { if (it.isNotEmpty()) return it }
        // tolerate a trailing/leading dot or a "Class.member" style tail
        byName[r.substringAfterLast('.')]?.let { if (it.isNotEmpty()) return it }
        return emptyList()
    }

    /** Declarations that list [type] (by simple name) among their supertypes. */
    fun subtypesOf(type: String): List<CodeDecl> {
        val simple = type.substringAfterLast('.').trim()
        return decls.filter { d -> d.supertypes.any { it.substringBefore('<').substringAfterLast('.').trim() == simple } }
            .sortedWith(compareBy({ it.module }, { it.fqn }))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        val instance: CodeStore by lazy { load() }

        fun load(): CodeStore {
            fun resource(name: String): String =
                CodeStore::class.java.getResourceAsStream(name)
                    ?.bufferedReader()?.readText()
                    ?: error("missing bundled resource $name (run the build so generateCodeContent produces it)")
            return CodeStore(
                decls = json.decodeFromString(ListSerializer(CodeDecl.serializer()), resource("/code/chunks.json")),
                meta = json.decodeFromString(CodeMeta.serializer(), resource("/code/meta.json")),
            )
        }
    }
}
