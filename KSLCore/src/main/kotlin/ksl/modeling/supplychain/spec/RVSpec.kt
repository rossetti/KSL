package ksl.modeling.supplychain.spec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Serializable description of a random variable used in a
 * [NetworkSpec] (an item lead time, a transport time, an
 * inter-arrival time, a periodic review interval).
 *
 * Pure data: an `RVSpec` carries no reference to a KSL `RVariableIfc`.
 * `ksl.modeling.supplychain.spec.SupplyChainBuilder` materializes each
 * spec into the corresponding KSL random variable with its stream
 * number (D2).  Keeping the spec free of runtime types lets a
 * [NetworkSpec] be authored, serialized, diffed, and generated without
 * a `Model`.
 *
 * Stream numbers are **explicit** (a field on every stochastic
 * variant) per the DSL plan §1 — reproducibility is the modeler's
 * responsibility, and a saved spec must be deterministic.  The Kotlin
 * DSL (D4) adds an `autoStream()` allocator that materializes an
 * assigned number into the spec.
 *
 * v1 covers the variants the existing examples use ([Constant],
 * [Exponential]) plus a few common continuous distributions
 * ([Uniform], [Triangular], [Lognormal]).  Additional families are a
 * mechanical follow-up: add a variant here and a branch in the
 * builder's materializer.
 */
@Serializable
sealed class RVSpec {

    /** A deterministic constant value (no stream). */
    @Serializable
    @SerialName("constant")
    data class Constant(val value: Double) : RVSpec()

    /** Exponential with the given [mean] and [stream] number. */
    @Serializable
    @SerialName("exponential")
    data class Exponential(val mean: Double, val stream: Int) : RVSpec()

    /** Continuous uniform on `[min, max]` with the given [stream]. */
    @Serializable
    @SerialName("uniform")
    data class Uniform(val min: Double, val max: Double, val stream: Int) : RVSpec()

    /** Triangular with the given [min], [mode], [max] and [stream]. */
    @Serializable
    @SerialName("triangular")
    data class Triangular(
        val min: Double,
        val mode: Double,
        val max: Double,
        val stream: Int,
    ) : RVSpec()

    /** Lognormal with the given [mean], [variance] and [stream]. */
    @Serializable
    @SerialName("lognormal")
    data class Lognormal(
        val mean: Double,
        val variance: Double,
        val stream: Int,
    ) : RVSpec()
}
