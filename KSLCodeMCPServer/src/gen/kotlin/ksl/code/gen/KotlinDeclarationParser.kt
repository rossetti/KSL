package ksl.code.gen

import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtilRt
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTypeAlias

/**
 * A declaration extracted from a .kt file, without its (later, uniquified) id.
 * Cross-references (usedInExamples) and topics are filled in afterwards.
 */
data class RawDecl(
    val module: String,
    val kind: String,
    val fqn: String,
    val name: String,
    val pkg: String,
    val signature: String,
    val kdoc: String?,
    val supertypes: List<String>,
    val members: List<String>,
    val file: String,
    val lineStart: Int,
    val lineEnd: Int,
)

/**
 * Parses Kotlin source into public API declarations using the Kotlin compiler's
 * PSI — the same parser the compiler uses, so every declaration form (generics,
 * annotations, `@JvmOverloads constructor`, sealed hierarchies, extension
 * functions, companions, expression bodies) is handled correctly without a full
 * compile or type resolution. One environment is reused across all files.
 *
 * Only the public/protected API is indexed: private and internal declarations are
 * implementation detail and would only dilute student searches.
 */
class KotlinDeclarationParser : AutoCloseable {

    private val disposable = Disposer.newDisposable("ksl-code-extract")
    private val factory: KtPsiFactory

    init {
        val configuration = CompilerConfiguration().apply {
            put(CommonConfigurationKeys.MODULE_NAME, "ksl-code-extract")
            put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        }
        val env = KotlinCoreEnvironment.createForProduction(
            disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
        factory = KtPsiFactory(env.project, markGenerated = false)
    }

    override fun close() = Disposer.dispose(disposable)

    /**
     * Extracts every public top-level and nested type, plus top-level and
     * extension functions and type aliases, from [text]. [relPath] is the
     * repo-relative source path recorded on each declaration.
     */
    fun parse(text: String, module: String, relPath: String): List<RawDecl> {
        // PSI requires \n-only text: KtPsiFactory wraps the string in a LightVirtualFile,
        // which skips the line-separator normalization a real file load performs. Given CRLF
        // (any Windows checkout — Git defaults core.autocrlf=true) the tree comes back
        // truncated with an empty packageFqName. PSI offsets index this normalized text, so
        // every read below must use it rather than the caller's string.
        val src = StringUtilRt.convertLineSeparators(text)
        val ktFile: KtFile = factory.createFile(relPath.substringAfterLast('/'), src)
        val pkg = ktFile.packageFqName.asString()
        val lines = LineMap(src)
        val out = ArrayList<RawDecl>()
        ktFile.declarations.forEach { collect(it, module, pkg, relPath, src, lines, out) }
        return out
    }

    /** Recurse into public types (for nested classes/objects); emit one RawDecl per declaration. */
    private fun collect(
        d: KtDeclaration,
        module: String,
        pkg: String,
        relPath: String,
        text: String,
        lines: LineMap,
        out: MutableList<RawDecl>,
    ) {
        if (!d.isPublicApi()) return
        val kind = kindOf(d) ?: return
        val name = (d as? KtNamedDeclaration)?.name ?: return
        val fqn = (d as? KtNamedDeclaration)?.fqName?.asString()
            ?: if (pkg.isEmpty()) name else "$pkg.$name"

        out += RawDecl(
            module = module,
            kind = kind,
            fqn = fqn,
            name = name,
            pkg = pkg,
            signature = signatureOf(d, text),
            kdoc = kdocOf(d)?.let { cleanKdoc(it.text) },
            supertypes = (d as? KtClassOrObject)?.superTypeListEntries
                ?.mapNotNull { it.typeReference?.text?.replace(Regex("\\s+"), " ")?.trim() }
                ?: emptyList(),
            members = memberSignatures(d, text),
            file = relPath,
            lineStart = lines.lineOf(d.textRange.startOffset),
            lineEnd = lines.lineOf(d.textRange.endOffset),
        )

        // Nested public types become their own chunks; member functions/properties
        // are summarized on the enclosing chunk (above), not indexed separately.
        if (d is KtClassOrObject) {
            d.declarations.forEach {
                if (it is KtClassOrObject) collect(it, module, pkg, relPath, text, lines, out)
            }
        }
    }

    /** Public/protected member signatures — the visible API surface of a type. */
    private fun memberSignatures(d: KtDeclaration, text: String): List<String> {
        if (d !is KtClassOrObject) return emptyList()
        return d.declarations
            .filter { (it is KtNamedFunction || it is KtProperty) && it.isPublicApi() }
            .map { signatureOf(it, text) }
            .filter { it.isNotBlank() }
    }

    private fun kindOf(d: KtDeclaration): String? = when (d) {
        is KtClass -> when {
            d.isInterface() -> "interface"
            d.isEnum() -> "enum class"
            d.isAnnotation() -> "annotation class"
            d.isSealed() -> "sealed class"
            d.isData() -> "data class"
            d.hasModifier(KtTokens.ABSTRACT_KEYWORD) -> "abstract class"
            else -> "class"
        }
        is KtObjectDeclaration -> if (d.isCompanion()) "companion object" else "object"
        is KtNamedFunction -> if (d.receiverTypeReference != null) "extension_fun" else "fun"
        is KtTypeAlias -> "type alias"
        else -> null // properties, init blocks, etc. are not standalone chunks
    }

    /**
     * The declaration header, KDoc and body stripped: modifiers, keyword, name,
     * type parameters, (primary constructor) parameters, and return/supertype
     * text — everything up to the `{` body or `=` expression body.
     */
    private fun signatureOf(d: KtDeclaration, text: String): String {
        val start = (kdocOf(d)?.textRange?.endOffset ?: d.textRange.startOffset)
        val cut = when (d) {
            is KtClassOrObject -> d.body?.textRange?.startOffset
            is KtNamedFunction -> d.bodyExpression?.textRange?.startOffset
            is KtProperty -> listOfNotNull(
                d.initializer?.textRange?.startOffset,
                d.getter?.textRange?.startOffset,
                d.setter?.textRange?.startOffset,
                d.delegateExpression?.textRange?.startOffset,
            ).minOrNull()
            else -> null
        } ?: d.textRange.endOffset
        val end = cut.coerceAtLeast(start)
        return text.substring(start, end).trim()
            .removeSuffix("=").trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun kdocOf(d: KtDeclaration): KDoc? {
        var child = d.firstChild
        while (child != null) {
            if (child is KDoc) return child
            child = child.nextSibling
        }
        return null
    }

    /** A declaration is public API unless explicitly private or internal (protected is subclass API). */
    private fun KtModifierListOwner.isPublicApi(): Boolean =
        !hasModifier(KtTokens.PRIVATE_KEYWORD) && !hasModifier(KtTokens.INTERNAL_KEYWORD)
}

/** Maps character offsets to 1-based line numbers for a single file. */
private class LineMap(text: String) {
    private val starts = buildList {
        add(0)
        text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
    }

    fun lineOf(offset: Int): Int {
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo + 1
    }
}

/** Strips comment markers and per-line ` * ` prefixes from a raw KDoc block. */
internal fun cleanKdoc(raw: String): String {
    val inner = raw.trim().removePrefix("/**").removeSuffix("*/")
    return inner.lines()
        .joinToString("\n") { line ->
            val t = line.trimStart()
            (if (t.startsWith("*")) t.removePrefix("*").let { if (it.startsWith(" ")) it.substring(1) else it } else line.trim())
        }
        .trim()
        .ifBlank { "" }
}
