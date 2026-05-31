package ksl.simulation

import ksl.utilities.random.rvariable.parameters.RVParameterSetter

/**
 *  The engine that assembles a [ModelCatalog] for a model.  It is both the
 *  [ElementCatalogScope] fed to each element's [ModelElement.specifyCatalog]
 *  during roll-up and the [CatalogCurationScope] fed to each [Model.curateCatalog]
 *  block during model-level curation.  Created and driven only by [Model]; never
 *  constructed directly.
 *
 *  Assembly is two-phase (see [assemble]):
 *
 *  1. **Element roll-up** (lenient): the element tree is walked in creation order
 *     and each element's `specifyCatalog` contributes nominations, each tagged
 *     with the contributing element's id (provenance).  Because model-element
 *     names are unique, nominated keys are globally unique, so cross-element
 *     collisions cannot occur; should two elements nominate the *same* item
 *     (e.g. a parent and its child both nominate the child's response), the first
 *     wins.  An invalid element nomination is recorded as a problem and skipped —
 *     never thrown — so a buggy reusable element cannot crash a consuming model.
 *
 *  2. **Model curation** (strict): each `curateCatalog` block runs, able to add
 *     (overriding an element's nomination of the same key — model metadata wins),
 *     remove, or clear.  An invalid model-level nomination throws.
 *
 *  Provenance is kept only here; it powers [denominateAllFrom] / [denominateSubtree]
 *  and never enters the serialized [ModelCatalog].
 */
class ModelCatalogBuilder internal constructor(private val model: Model) : CatalogCurationScope {

    private val inputs = LinkedHashMap<String, NominatedInput>()
    private val inputSource = HashMap<String, Int?>()
    private val outputs = LinkedHashMap<String, NominatedOutput>()
    private val outputSource = HashMap<String, Int?>()
    private val problems = mutableListOf<String>()

    private var lenient = false
    private var currentSource: Int? = null

    private val cc: Char
        get() = RVParameterSetter.rvParamConCatChar

    // ── Adds (core ElementCatalogScope surface) ──────────────────────────────

    override fun input(key: String, configure: NominationSpec.() -> Unit) {
        val spec = NominationSpec().apply(configure)
        val kind = inputKindOf(key)
        if (kind == null) {
            problem("No control named '$key'.${didYouMean(key, controlCandidates())}")
            return
        }
        if (lenient && inputs.containsKey(key)) return   // first contributor wins
        inputs[key] = NominatedInput(key, kind, spec.displayName, spec.description, spec.unit)
        inputSource[key] = currentSource
    }

    override fun rvParameter(rvName: String, paramName: String, configure: NominationSpec.() -> Unit) {
        val spec = NominationSpec().apply(configure)
        val key = "$rvName$cc$paramName"
        if (!model.rvParameterSetter.containsParameter(rvName, paramName)) {
            problem("No random-variable parameter '$key'.${didYouMean(key, rvCandidates())}")
            return
        }
        if (lenient && inputs.containsKey(key)) return
        inputs[key] = NominatedInput(key, NominatedInputKind.RV_PARAMETER, spec.displayName, spec.description, spec.unit)
        inputSource[key] = currentSource
    }

    override fun output(name: String, configure: NominationSpec.() -> Unit) {
        val spec = NominationSpec().apply(configure)
        if (name !in model.responseNames) {
            problem("No response or counter named '$name'.${didYouMean(name, model.responseNames)}")
            return
        }
        if (lenient && outputs.containsKey(name)) return
        outputs[name] = NominatedOutput(name, spec.displayName, spec.description, spec.unit)
        outputSource[name] = currentSource
    }

    // ── Removes (core CatalogCurationScope surface) ──────────────────────────

    override fun denominateInput(key: String) {
        inputs.remove(key); inputSource.remove(key)
    }

    override fun denominateOutput(name: String) {
        outputs.remove(name); outputSource.remove(name)
    }

    override fun denominateAllFrom(element: ModelElement) = removeBySource(setOf(element.id))

    override fun denominateSubtree(element: ModelElement) {
        val ids = mutableSetOf<Int>()
        element.collectSubtreeIds(ids)
        removeBySource(ids)
    }

    override fun denominateInputs(predicate: (NominatedInput) -> Boolean) {
        inputs.values.filter(predicate).map { it.key }.forEach { denominateInput(it) }
    }

    override fun denominateOutputs(predicate: (NominatedOutput) -> Boolean) {
        outputs.values.filter(predicate).map { it.name }.forEach { denominateOutput(it) }
    }

    override fun clearElementNominations() {
        inputSource.filterValues { it != null }.keys.toList().forEach { denominateInput(it) }
        outputSource.filterValues { it != null }.keys.toList().forEach { denominateOutput(it) }
    }

    private fun removeBySource(ids: Set<Int>) {
        inputSource.filterValues { it in ids }.keys.toList().forEach { denominateInput(it) }
        outputSource.filterValues { it in ids }.keys.toList().forEach { denominateOutput(it) }
    }

    // ── Orchestration (called only by Model) ─────────────────────────────────

    internal fun beginSource(id: Int) { currentSource = id }
    internal fun endSource() { currentSource = null }

    /**
     *  Runs the element roll-up then the model curation, returning the assembled
     *  catalog (or `null` when empty) paired with any problems recorded during the
     *  lenient roll-up phase.
     */
    internal fun assemble(
        rollUp: (ModelCatalogBuilder) -> Unit,
        curation: List<CatalogCurationScope.() -> Unit>
    ): Pair<ModelCatalog?, List<String>> {
        lenient = true
        currentSource = null
        rollUp(this)
        lenient = false
        currentSource = null
        for (block in curation) this.block()
        val catalog = ModelCatalog(inputs.values.toList(), outputs.values.toList())
        return (if (catalog.isEmpty) null else catalog) to problems.toList()
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun problem(message: String) {
        problems += message
        if (!lenient) throw IllegalArgumentException(message)
    }

    private fun inputKindOf(key: String): NominatedInputKind? {
        val controls = model.controls()
        return when (key) {
            in controls.controlKeys()       -> NominatedInputKind.NUMERIC_CONTROL
            in controls.stringControlKeys() -> NominatedInputKind.STRING_CONTROL
            in controls.jsonControlKeys()   -> NominatedInputKind.JSON_CONTROL
            else -> null
        }
    }

    private fun controlCandidates(): Set<String> {
        val controls = model.controls()
        return controls.controlKeys() + controls.stringControlKeys() + controls.jsonControlKeys()
    }

    private fun rvCandidates(): Set<String> =
        model.rvParameterSetter.flatParametersAsDoubles.keys

    private fun didYouMean(target: String, candidates: Collection<String>): String {
        if (candidates.isEmpty()) return ""
        val threshold = maxOf(2, target.length / 3)
        val ranked = candidates
            .map { it to levenshtein(target, it) }
            .filter { it.second <= threshold }
            .sortedBy { it.second }
            .take(3)
            .map { it.first }
        return if (ranked.isEmpty()) "" else "  Did you mean ${ranked.joinToString(", ") { "'$it'" }}?"
    }

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(curr, 0, prev, 0, curr.size)
        }
        return prev[b.length]
    }
}
