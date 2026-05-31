package ksl.simulation

import kotlinx.serialization.Serializable

/**
 *  The family a [NominatedInput] belongs to.  Derived automatically when an
 *  input is nominated, so the author never supplies it: a control key resolves
 *  to one of the three control families, and [ModelCatalogBuilder.rvParameter]
 *  stamps [RV_PARAMETER].
 */
@Serializable
enum class NominatedInputKind { NUMERIC_CONTROL, STRING_CONTROL, JSON_CONTROL, RV_PARAMETER }

/**
 *  One author-nominated input — a control or random-variable parameter the
 *  model developer has flagged as worth surfacing first in an application.
 *
 *  This is a *reference* into the model's full input surface, not a copy:
 *  [key] joins back to a [ksl.controls.ControlData] (for the three control
 *  families) or to a [ksl.utilities.random.rvariable.parameters.RVParameterData]
 *  (for [NominatedInputKind.RV_PARAMETER]) carried by the same
 *  [ModelDescriptor].  Bounds, type, and current value live on those DTOs;
 *  the nomination adds only salience and lean, model-semantic labelling.
 *
 *  @param key          the control keyName ("elementName.propertyName") or the
 *                      flattened RV-parameter key ("rvName<concat>paramName")
 *  @param kind         which family this input belongs to
 *  @param displayName  optional human label
 *  @param description  optional one-line description of what the input means
 *  @param unit         optional unit of measure (e.g. "minutes", "servers")
 */
@Serializable
data class NominatedInput(
    val key: String,
    val kind: NominatedInputKind,
    val displayName: String? = null,
    val description: String? = null,
    val unit: String? = null,
)

/**
 *  One author-nominated output — a response or counter the model developer has
 *  flagged as a headline result.  [name] joins back to a name in
 *  [ModelDescriptor.responseNames].
 *
 *  @param name         the response or counter name
 *  @param displayName  optional human label
 *  @param description  optional one-line description of what the output measures
 *  @param unit         optional unit of measure
 */
@Serializable
data class NominatedOutput(
    val name: String,
    val displayName: String? = null,
    val description: String? = null,
    val unit: String? = null,
)

/**
 *  An optional, author-curated catalog of a model's most important inputs and
 *  outputs, layered over the exhaustive [ModelDescriptor].  Applications may
 *  use it to focus their UX (surface the salient knobs first, pre-select the
 *  headline outputs) but must not depend on it being present.
 *
 *  List order conveys priority.  Produced by [Model.nominate] /
 *  [Model.tryNominate] and read back via [ModelDescriptor.catalog].
 *
 *  @param nominatedInputs  the nominated inputs, in author-declared order
 *  @param nominatedOutputs the nominated outputs, in author-declared order
 */
@Serializable
data class ModelCatalog(
    val nominatedInputs:  List<NominatedInput>  = emptyList(),
    val nominatedOutputs: List<NominatedOutput> = emptyList(),
) {
    /** True when nothing has been nominated. */
    val isEmpty: Boolean
        get() = nominatedInputs.isEmpty() && nominatedOutputs.isEmpty()
}

/**
 *  Outcome of the non-throwing [Model.tryNominate].  Valid nominations are
 *  applied; rejected ones are collected here as human-readable messages.
 *
 *  @param problems one message per rejected nomination; empty when all accepted
 */
data class NominationResult(val problems: List<String>) {
    /** True when every nomination in the block was accepted. */
    val isValid: Boolean
        get() = problems.isEmpty()
}
