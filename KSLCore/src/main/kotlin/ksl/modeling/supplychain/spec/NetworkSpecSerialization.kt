package ksl.modeling.supplychain.spec

import kotlinx.serialization.json.Json
import net.peanuuutz.tomlkt.Toml

/**
 * JSON / TOML codecs for [NetworkSpec] (DSL plan Phase D3).
 *
 * Thin wrappers over `kotlinx.serialization` and `tomlkt`: the same
 * `@Serializable` DTOs drive both formats, with the sealed-class
 * discriminator carried in the `type` field (`@SerialName`).  Both
 * directions are provided so a spec can be saved, hand-edited, and
 * reloaded; the round-trip is lossless (`fromX(toX(spec)) == spec`).
 *
 * TOML is the friendlier hand-authoring format — it supports comments
 * and array-of-tables (`[[nodes]]`) — while JSON is convenient for
 * tooling and interchange.
 */

/** JSON codec: pretty output, tolerant of unknown keys on load. */
private val networkSpecJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

/** TOML codec: omits defaults on save, tolerant of unknown keys on load. */
private val networkSpecToml = Toml {
    ignoreUnknownKeys = true
}

/** Serialize this spec to a JSON string. */
fun NetworkSpec.toJson(): String =
    networkSpecJson.encodeToString(NetworkSpec.serializer(), this)

/** Serialize this spec to a TOML string. */
fun NetworkSpec.toToml(): String =
    networkSpecToml.encodeToString(NetworkSpec.serializer(), this)

/** Parse a [NetworkSpec] from a JSON string produced by [toJson]. */
fun NetworkSpec.Companion.fromJson(text: String): NetworkSpec =
    networkSpecJson.decodeFromString(NetworkSpec.serializer(), text)

/** Parse a [NetworkSpec] from a TOML string produced by [toToml]. */
fun NetworkSpec.Companion.fromToml(text: String): NetworkSpec =
    networkSpecToml.decodeFromString(NetworkSpec.serializer(), text)
