package ksl.modeling.decision

/** STUB — Appendix E.2, §4.4. */

sealed interface PreparedAction {
    data class Ready(val plan: ActionPlan) : PreparedAction
    data class Invalid(val violations: List<String>) : PreparedAction
}

class ActionPlan internal constructor(internal val steps: List<Step>) {

    /** Inert view: what will be written, in order. Holding one cannot write anything. */
    val writes: List<PlannedWrite> = steps.map { PlannedWrite(it.name, it.from, it.to) }

    data class PlannedWrite(val name: String, val from: Double, val to: Double)

    internal class Step(
        val name: String, val from: Double, val to: Double, val actuator: LeverActuator)
}

interface ActionBinding {
    fun prepare(action: DoubleArray): PreparedAction
    fun apply(plan: ActionPlan)
}

class ActionValidationException(val violations: List<String>) :
    RuntimeException("Infeasible action; no lever was written. Violations: $violations")

class ActionApplicationException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class BindingException(val unresolved: String, val available: List<String>) :
    RuntimeException("Cannot resolve '$unresolved'. Available: $available")

class NarrowingException(message: String) : RuntimeException(message)

class RewardKindException(message: String) : RuntimeException(message)
