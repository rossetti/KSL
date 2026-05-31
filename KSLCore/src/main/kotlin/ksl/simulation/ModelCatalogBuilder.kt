package ksl.simulation

import ksl.controls.ControlIfc
import ksl.controls.JsonControlIfc
import ksl.controls.StringControlIfc
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.RandomVariableCIfc
import ksl.modeling.variable.ResponseCIfc
import ksl.utilities.random.rvariable.parameters.RVParameterSetter
import kotlin.reflect.KProperty1

/**
 *  Mutable holder for the lean metadata attached to a single nomination.
 *  Configured inside the trailing lambda of a [ModelCatalogBuilder] method:
 *
 *  ```
 *  output(systemTime) { displayName = "Avg Time in System"; unit = "min" }
 *  ```
 */
class NominationSpec internal constructor() {
    /** Optional human label. */
    var displayName: String? = null
    /** Optional one-line description of what the item means. */
    var description: String? = null
    /** Optional unit of measure (e.g. "minutes", "servers"). */
    var unit: String? = null
}

/**
 *  Validated, model-bound accumulator for a [ModelCatalog].  Created and driven
 *  only by [Model.nominate] / [Model.tryNominate]; never constructed directly.
 *
 *  Every nomination is validated against the model the instant it is declared.
 *  An author can nominate either by **string key** (the canonical control key,
 *  RV name + parameter, or response name) or — preferably — by **object
 *  reference** when they already hold the [ksl.controls.ControlIfc],
 *  [ksl.modeling.variable.ResponseCIfc], [ksl.modeling.variable.CounterCIfc],
 *  [ksl.modeling.variable.RandomVariableCIfc], or model element.  The instance
 *  overloads derive the key/name from the object, so the author neither formats
 *  keys by hand nor guesses an auto-generated random-variable name; for a
 *  control they may also pass a property reference.  Both paths share the same
 *  validation, de-duplication, and "did you mean" suggestions, so even an
 *  instance from a *different* model is rejected cleanly.
 *
 *  Validation behaviour depends on the entry point: [Model.nominate] is
 *  fail-fast (throws `IllegalArgumentException` on the first bad or duplicate
 *  nomination), while [Model.tryNominate] applies the valid nominations and
 *  collects the rest into a [NominationResult].  Nominations accumulate across
 *  calls; declare them after the model element graph is complete.
 */
class ModelCatalogBuilder internal constructor(private val model: Model) {

    private val inputs = mutableListOf<NominatedInput>()
    private val outputs = mutableListOf<NominatedOutput>()
    private val seenInputKeys = mutableSetOf<String>()
    private val seenOutputNames = mutableSetOf<String>()
    private val problems = mutableListOf<String>()
    private var throwOnError = true

    private val cc: Char
        get() = RVParameterSetter.rvParamConCatChar

    // ── String-keyed DSL (the validating core the overloads delegate into) ──

    /** Nominate a numeric, string, or JSON control by its key ("elementName.propertyName"). */
    fun input(key: String, configure: NominationSpec.() -> Unit = {}) {
        val spec = NominationSpec().apply(configure)
        val kind = inputKindOf(key)
        if (kind == null) {
            record("No control named '$key'.${didYouMean(key, controlCandidates())}")
            return
        }
        if (!seenInputKeys.add(key)) {
            record("Input '$key' was nominated more than once.")
            return
        }
        inputs += NominatedInput(key, kind, spec.displayName, spec.description, spec.unit)
    }

    /** Nominate a random-variable parameter (e.g. rvParameter("ServiceTimeRV", "mean")). */
    fun rvParameter(rvName: String, paramName: String, configure: NominationSpec.() -> Unit = {}) {
        val spec = NominationSpec().apply(configure)
        val key = "$rvName$cc$paramName"
        if (!model.rvParameterSetter.containsParameter(rvName, paramName)) {
            record("No random-variable parameter '$key'.${didYouMean(key, rvCandidates())}")
            return
        }
        if (!seenInputKeys.add(key)) {
            record("Input '$key' was nominated more than once.")
            return
        }
        inputs += NominatedInput(key, NominatedInputKind.RV_PARAMETER, spec.displayName, spec.description, spec.unit)
    }

    /** Nominate a response or counter by name. */
    fun output(name: String, configure: NominationSpec.() -> Unit = {}) {
        val spec = NominationSpec().apply(configure)
        if (name !in model.responseNames) {
            record("No response or counter named '$name'.${didYouMean(name, model.responseNames)}")
            return
        }
        if (!seenOutputNames.add(name)) {
            record("Output '$name' was nominated more than once.")
            return
        }
        outputs += NominatedOutput(name, spec.displayName, spec.description, spec.unit)
    }

    // ── Instance overloads — derive the key/name from the object in hand ────

    /** Nominate a numeric/boolean control the author already holds. */
    fun input(control: ControlIfc, configure: NominationSpec.() -> Unit = {}) =
        input(control.keyName, configure)

    /** Nominate a string control the author already holds. */
    fun input(control: StringControlIfc, configure: NominationSpec.() -> Unit = {}) =
        input(control.keyName, configure)

    /** Nominate a JSON control the author already holds. */
    fun input(control: JsonControlIfc, configure: NominationSpec.() -> Unit = {}) =
        input(control.keyName, configure)

    /** Nominate a control by its owning element plus the annotated property's name. */
    fun input(element: ModelElement, propertyName: String, configure: NominationSpec.() -> Unit = {}) =
        input("${element.name}.$propertyName", configure)

    /**
     *  Nominate a control by its owning element plus a property reference, e.g.
     *  `input(server, ResourceWithQ::numServers)`.  Compile-time-checked against
     *  the element's type and rename-safe.
     */
    fun <T : ModelElement> input(element: T, property: KProperty1<T, *>, configure: NominationSpec.() -> Unit = {}) =
        input("${element.name}.${property.name}", configure)

    /** Nominate a random-variable parameter; the RV's name comes from the object. */
    fun rvParameter(rv: RandomVariableCIfc, paramName: String, configure: NominationSpec.() -> Unit = {}) =
        rvParameter(rv.name, paramName, configure)

    /** Nominate a response the author already holds. */
    fun output(response: ResponseCIfc, configure: NominationSpec.() -> Unit = {}) =
        output(response.name, configure)

    /** Nominate a counter the author already holds. */
    fun output(counter: CounterCIfc, configure: NominationSpec.() -> Unit = {}) =
        output(counter.name, configure)

    // ── Internals ───────────────────────────────────────────────────────────

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

    private fun record(message: String) {
        problems += message
        if (throwOnError) throw IllegalArgumentException(message)
    }

    /** Returns a " Did you mean …?" suffix for the nearest candidates, or "" when none are close. */
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

    /** Runs [block] in the given error mode, returning the problems it produced. */
    internal fun run(throwing: Boolean, block: ModelCatalogBuilder.() -> Unit): List<String> {
        val start = problems.size
        val previous = throwOnError
        throwOnError = throwing
        try {
            this.block()
        } finally {
            throwOnError = previous
        }
        return problems.subList(start, problems.size).toList()
    }

    /** Immutable snapshot of everything nominated so far. */
    internal fun build(): ModelCatalog = ModelCatalog(inputs.toList(), outputs.toList())
}
