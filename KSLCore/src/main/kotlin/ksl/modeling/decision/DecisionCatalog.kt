package ksl.modeling.decision

import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc

/** STUB — Appendix E.2, §4.3.1. Built only by DecisionElementBuilder. */
class DecisionCatalog internal constructor(
    val owner: ModelElement,
    private val observations: Map<String, GetValueIfc>,
    private val actuators: Map<String, LeverActuator>,
    private val leverInfos: Map<String, LeverInfo>,
    private val rewardSources: Map<String, RewardSourceCIfc>,
    val observationNames: List<String>,
    val leverNames: List<String>
) {
    val name: String get() = owner.name

    // ---- Descriptive: safe to publish.
    fun observation(name: String): GetValueIfc? = observations[name]
    fun rewardSource(name: String): RewardSourceCIfc? = rewardSources[name]
    fun leverInfo(name: String): LeverInfo? = leverInfos[name]

    // ---- Effectful: reachable only by the binding machinery in this module.
    internal fun actuator(name: String): LeverActuator? = actuators[name]
}
