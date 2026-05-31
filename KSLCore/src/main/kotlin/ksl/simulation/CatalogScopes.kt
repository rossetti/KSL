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
 *  Mutable holder for the lean metadata attached to a single nomination,
 *  configured inside the trailing lambda of a catalog-scope method:
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
 *  Additive-only nomination surface handed to a model element's
 *  [ModelElement.specifyCatalog] override.  An element nominates the inputs and
 *  outputs it considers salient — preferably by passing the object instances it
 *  already holds (a control, response, counter, random variable, or a property
 *  of one of its own model elements), so keys and names are derived rather than
 *  hand-formatted.  String-keyed forms are available as well.
 *
 *  The instance forms are interface defaults that delegate to the three core
 *  string-keyed methods; an implementation need only provide those three.
 */
interface ElementCatalogScope {

    /** Nominate a numeric/string/JSON control by its key ("elementName.propertyName"). */
    fun input(key: String, configure: NominationSpec.() -> Unit = {})

    /** Nominate a random-variable parameter (e.g. rvParameter("ServiceTimeRV", "mean")). */
    fun rvParameter(rvName: String, paramName: String, configure: NominationSpec.() -> Unit = {})

    /** Nominate a response or counter by name. */
    fun output(name: String, configure: NominationSpec.() -> Unit = {})

    /** Nominate a numeric/boolean control the element already holds. */
    fun input(control: ControlIfc, configure: NominationSpec.() -> Unit = {}) =
        input(control.keyName, configure)

    /** Nominate a string control the element already holds. */
    fun input(control: StringControlIfc, configure: NominationSpec.() -> Unit = {}) =
        input(control.keyName, configure)

    /** Nominate a JSON control the element already holds. */
    fun input(control: JsonControlIfc, configure: NominationSpec.() -> Unit = {}) =
        input(control.keyName, configure)

    /** Nominate a control by its owning element plus the annotated property's name. */
    fun input(element: ModelElement, propertyName: String, configure: NominationSpec.() -> Unit = {}) =
        input("${element.name}.$propertyName", configure)

    /** Nominate a control by element plus a property reference, e.g. input(server, Server::numServers). */
    fun <T : ModelElement> input(element: T, property: KProperty1<T, *>, configure: NominationSpec.() -> Unit = {}) =
        input("${element.name}.${property.name}", configure)

    /** Nominate a random-variable parameter; the RV's name comes from the object. */
    fun rvParameter(rv: RandomVariableCIfc, paramName: String, configure: NominationSpec.() -> Unit = {}) =
        rvParameter(rv.name, paramName, configure)

    /** Nominate a response the element already holds. */
    fun output(response: ResponseCIfc, configure: NominationSpec.() -> Unit = {}) =
        output(response.name, configure)

    /** Nominate a counter the element already holds. */
    fun output(counter: CounterCIfc, configure: NominationSpec.() -> Unit = {}) =
        output(counter.name, configure)
}

/**
 *  The model-assembly curation surface handed to [Model.curateCatalog].  Extends
 *  [ElementCatalogScope] with the ability to *remove* nominations contributed by
 *  the element roll-up — the tool for pruning a catalog that a heavily-reused
 *  element has over-populated — and to override an element's nomination (a plain
 *  re-nomination of the same key replaces it; the model-level metadata wins).
 *
 *  As with the element scope, the instance and convenience forms are defaults
 *  delegating to the core methods.
 */
interface CatalogCurationScope : ElementCatalogScope {

    /** Remove the nominated input with this key, if present. */
    fun denominateInput(key: String)

    /** Remove the nominated output with this name, if present. */
    fun denominateOutput(name: String)

    /** Remove every nomination contributed by [element]'s own `specifyCatalog`. */
    fun denominateAllFrom(element: ModelElement)

    /** Remove every nomination contributed by [element] or any element beneath it. */
    fun denominateSubtree(element: ModelElement)

    /** Remove every nominated input matching [predicate]. */
    fun denominateInputs(predicate: (NominatedInput) -> Boolean)

    /** Remove every nominated output matching [predicate]. */
    fun denominateOutputs(predicate: (NominatedOutput) -> Boolean)

    /** Drop ALL element-declared nominations, leaving only what this block adds. */
    fun clearElementNominations()

    /** Remove the nomination of this response, if present. */
    fun denominate(response: ResponseCIfc) = denominateOutput(response.name)

    /** Remove the nomination of this counter, if present. */
    fun denominate(counter: CounterCIfc) = denominateOutput(counter.name)

    /** Remove the nomination of this control, if present. */
    fun denominate(control: ControlIfc) = denominateInput(control.keyName)

    /** Remove the nomination of this string control, if present. */
    fun denominate(control: StringControlIfc) = denominateInput(control.keyName)

    /** Remove the nomination of this JSON control, if present. */
    fun denominate(control: JsonControlIfc) = denominateInput(control.keyName)

    /** Remove the nomination of this random-variable parameter, if present. */
    fun denominate(rv: RandomVariableCIfc, paramName: String) =
        denominateInput("${rv.name}${RVParameterSetter.rvParamConCatChar}$paramName")
}
