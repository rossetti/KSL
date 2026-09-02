package ksl.modeling.decision.descriptor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import net.peanuuutz.tomlkt.Toml

/**
 *  §14.1 — JSON and TOML codecs for [DecisionSurfaceDescriptor].
 *
 *  Thin wrappers over `kotlinx.serialization` and `tomlkt`, following the shape KSL already uses for
 *  `NetworkSpec` and `QueueingNetworkSpec`: the same `@Serializable` types drive both formats, both
 *  directions are provided, and the round trip is lossless. Both formats are offered because KSL
 *  already uses both — JSON for tooling and interchange, TOML for anything a person reads or edits.
 *
 *  **Reading a descriptor is not a way to configure a model.** ADR-4 rejected an authored descriptor
 *  as a second source of truth, and that decision is unchanged by these functions. A descriptor is
 *  *derived* from a declaration ([ksl.modeling.decision.DecisionElement.descriptor]) and travels
 *  outward: to a rule author who needs to know the shape of a surface, to an application that offers
 *  a list of levers, to an offline analysis that must interpret a stored trajectory's positional
 *  arrays. Nothing here builds a model, and nothing here should grow the ability to.
 *
 *  Because these functions accept text from outside, they check what they read. Two kinds of check,
 *  and the reasons differ:
 *
 *  - **Schema version.** A stored descriptor names the format it was written in, and a reader that
 *    does not understand that format must say so rather than silently drop the fields it did not
 *    recognise. See [DESCRIPTOR_SCHEMA_VERSION].
 *  - **Structure.** A hand-edited file can say things the declaration DSL would have refused —
 *    two levers with one name, a constraint over a lever that does not exist, a periodic epoch with
 *    no interval. Decoding is where that has to be caught, because after decoding it is just a data
 *    class and every consumer would have to check it again. The rules are the DSL's rules, so what
 *    a model cannot declare, a file cannot assert. See [validationProblems].
 */

/**
 *  The descriptor format this library writes, and the one it understands.
 *
 *  **Major** is the compatibility boundary: a descriptor with a different major version is refused
 *  on read, because a changed major means a field this reader would misinterpret rather than merely
 *  fail to see. **Minor** is additive — a descriptor written by a later minor version of the same
 *  major reads correctly here, losing only the fields this version has no name for, which is what
 *  `ignoreUnknownKeys` is for.
 *
 *  It is the *schema's* version, not the library's and not the model's, so it moves only when the
 *  descriptor's shape moves.
 */
val DESCRIPTOR_SCHEMA_VERSION: SchemaVersion = SchemaVersion(major = 2, minor = 0)

/** Pretty output so a written file is readable; tolerant of unknown keys so a later minor loads. */
private val descriptorJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    // A stored artifact must say what wrote it. With defaults omitted, a descriptor at the current
    // schema version would carry no version at all — exactly the file that is impossible to reject
    // later.
    encodeDefaults = true
    // Required, and for exactly the reason `ModelDescriptor` gives at `ModelDescriptor.kt:120`:
    // a lever's limits can be ±∞. "Order as much as you like" is an ordinary declaration, not an
    // edge case, and without this the codec cannot encode it at all — which is how this line was
    // found. The cost is real and is stated rather than hidden: the output is then JSON with a bare
    // `Infinity` in it, which is outside the JSON specification and which a strict external reader
    // — Python's `json`, a browser's `JSON.parse` — will reject. KSL already made this trade for
    // model descriptors and control bounds; a descriptor that cannot describe an unbounded lever
    // would be worse than one some external readers must be configured for. TOML has no such
    // problem: `inf` is native to it.
    allowSpecialFloatingPointValues = true
}

/**
 *  TOML has no null literal, so absent-means-null is the only encoding available: `explicitNulls`
 *  off omits a null field rather than inventing a representation for it, and the decoder's default
 *  puts the null back.
 */
private val descriptorToml = Toml {
    ignoreUnknownKeys = true
    explicitNulls = false
}

// ---------------------------------------------------------------------------- encoding

/** Serialize this descriptor to JSON. */
fun DecisionSurfaceDescriptor.toJson(): String =
    descriptorJson.encodeToString(DecisionSurfaceDescriptor.serializer(), this)

/** Serialize this descriptor to TOML. */
fun DecisionSurfaceDescriptor.toToml(): String =
    descriptorToml.encodeToString(DecisionSurfaceDescriptor.serializer(), this)

// ---------------------------------------------------------------------------- decoding

/**
 *  Parse a descriptor from JSON produced by [toJson].
 *
 *  @throws SchemaVersionException if the text names a major schema version this reader does not
 *    understand
 *  @throws IllegalArgumentException if the text decodes but describes a surface the declaration DSL
 *    would have refused; the message lists **every** problem found, not the first
 */
fun DecisionSurfaceDescriptor.Companion.fromJson(text: String): DecisionSurfaceDescriptor {
    val peeked = descriptorJson.parseToJsonElement(text).jsonObject["schemaVersion"]
        ?.let { descriptorJson.decodeFromJsonElement(SchemaVersion.serializer(), it) }
    checkReadable(peeked, "JSON")
    return descriptorJson.decodeFromString(DecisionSurfaceDescriptor.serializer(), text).checked()
}

/**
 *  Parse a descriptor from TOML produced by [toToml].
 *
 *  @throws SchemaVersionException if the text names a major schema version this reader does not
 *    understand
 *  @throws IllegalArgumentException if the text decodes but describes a surface the declaration DSL
 *    would have refused
 */
fun DecisionSurfaceDescriptor.Companion.fromToml(text: String): DecisionSurfaceDescriptor {
    val peeked = descriptorToml.parseToTomlTable(text)["schemaVersion"]
        ?.let { descriptorToml.decodeFromTomlElement(SchemaVersion.serializer(), it) }
    checkReadable(peeked, "TOML")
    return descriptorToml.decodeFromString(DecisionSurfaceDescriptor.serializer(), text).checked()
}

/**
 *  The version is read **before** the descriptor rather than after, so that a format this reader
 *  cannot understand is reported as a version problem rather than as whatever decoding error the
 *  unreadable fields happen to produce.
 *
 *  A descriptor with no version at all is treated as current: the field has a default, so its
 *  absence is indistinguishable from a writer that omitted defaults, and refusing it would make
 *  every hand-written fragment illegal for a reason a person cannot see.
 */
private fun checkReadable(version: SchemaVersion?, format: String) {
    val v = version ?: return
    if (v.major != DESCRIPTOR_SCHEMA_VERSION.major) {
        throw SchemaVersionException(
            "This $format descriptor is schema version ${v.major}.${v.minor}, and this library " +
                "reads major version ${DESCRIPTOR_SCHEMA_VERSION.major} " +
                "(currently ${DESCRIPTOR_SCHEMA_VERSION.major}.${DESCRIPTOR_SCHEMA_VERSION.minor}). " +
                "A different major version means a field would be read as something it is not, " +
                "which is why this is refused rather than read partially."
        )
    }
}

private fun DecisionSurfaceDescriptor.checked(): DecisionSurfaceDescriptor {
    val problems = validationProblems()
    require(problems.isEmpty()) {
        "This descriptor does not describe a surface that could have been declared. " +
            "${problems.size} problem(s):" + problems.joinToString("") { "\n  - $it" }
    }
    return this
}

// ---------------------------------------------------------------------------- validation

/**
 *  Every way this descriptor fails to describe a surface a model could have declared, or an empty
 *  list if it describes one.
 *
 *  Public because it is what an application validating a hand-edited file needs, and because a
 *  consumer that wants to *report* the problems rather than be thrown at should not have to catch an
 *  exception to enumerate them.
 *
 *  The rules are the declaration DSL's rules, deliberately: a descriptor is derived from a
 *  declaration, so a file asserting something the DSL refuses describes a model that cannot exist.
 *  Every problem is reported, not the first — a file with three mistakes should teach a person
 *  three things.
 */
fun DecisionSurfaceDescriptor.validationProblems(): List<String> {
    val p = mutableListOf<String>()

    if (name.isBlank()) p += "the element name is blank"

    fun namesOf(what: String, names: List<String>) {
        if (names.any { it.isBlank() }) p += "a $what has a blank name"
        val dupes = names.groupBy { it }.filterValues { it.size > 1 }.keys
        if (dupes.isNotEmpty()) {
            p += "$what names must be distinct, because a name is what a consumer resolves a " +
                "position by; these repeat: $dupes"
        }
    }
    namesOf("observation", observations.map { it.name })
    namesOf("lever", levers.map { it.name })
    namesOf("reward term", rewards.map { it.name })

    for (l in levers) {
        val where = "lever '${l.name}'"
        val bounds = listOf(l.modelLowerLimit, l.modelUpperLimit, l.lowerBound, l.upperBound)
        if (bounds.any { it.isNaN() }) {
            p += "$where has a NaN limit; NaN is never a bound"
            continue      // every comparison below is meaningless once a bound is NaN
        }
        if (l.modelLowerLimit > l.modelUpperLimit) {
            p += "$where declares an empty model envelope [${l.modelLowerLimit}, ${l.modelUpperLimit}]"
        }
        if (l.lowerBound > l.upperBound) {
            p += "$where declares empty bounds [${l.lowerBound}, ${l.upperBound}]"
        }
        if (l.lowerBound < l.modelLowerLimit || l.upperBound > l.modelUpperLimit) {
            p += "$where is narrowed to [${l.lowerBound}, ${l.upperBound}], which is outside its " +
                "model envelope [${l.modelLowerLimit}, ${l.modelUpperLimit}]. Narrowing may only " +
                "shrink: the model's limits are a physical fact and the experiment's are a choice"
        }
        when (l.domain) {
            LeverDomain.CATEGORICAL -> {
                val levels = l.levels
                if (levels.isNullOrEmpty()) {
                    p += "$where is CATEGORICAL and declares no levels, so its values stand for nothing"
                } else if (l.modelLowerLimit < 0.0 || l.modelUpperLimit > (levels.size - 1).toDouble()) {
                    p += "$where is CATEGORICAL with ${levels.size} level(s), so its envelope must " +
                        "be within [0, ${levels.size - 1}]; it is [${l.modelLowerLimit}, ${l.modelUpperLimit}]"
                }
            }
            LeverDomain.INTEGER -> {
                if (bounds.any { it.isFinite() && it != Math.rint(it) }) {
                    p += "$where has an INTEGER domain and a non-integral limit among $bounds"
                }
                if (l.levels != null) p += "$where declares levels but is not CATEGORICAL"
            }
            LeverDomain.CONTINUOUS -> {
                if (l.levels != null) p += "$where declares levels but is not CATEGORICAL"
            }
        }
    }

    val leverNames = levers.map { it.name }.toSet()
    for (c in constraints) {
        val kind = c::class.simpleName
        if (c.names.size < 2) {
            p += "$kind joins ${c.names.size} lever(s); a joint constraint over fewer than two " +
                "levers constrains nothing jointly"
        }
        val unknown = c.names.filterNot { it in leverNames }
        if (unknown.isNotEmpty()) {
            p += "$kind names $unknown, which are not declared levers of '$name'. Declared: " +
                "${leverNames.toList()}"
        }
        val repeated = c.names.groupBy { it }.filterValues { it.size > 1 }.keys
        if (repeated.isNotEmpty()) p += "$kind names $repeated more than once"
        val total = when (c) {
            is SumEquals -> c.total
            is SumAtMost -> c.total
        }
        if (!total.isFinite()) p += "$kind has a non-finite total of $total"
    }
    val constrainedTwice = constraints.flatMap { it.names }
        .groupBy { it }.filterValues { it.size > 1 }.keys
    if (constrainedTwice.isNotEmpty()) {
        p += "these levers are named by more than one joint constraint: $constrainedTwice. The " +
            "element refuses that at declaration, because which constraint governs a repair is " +
            "then undefined"
    }

    for (r in rewards) {
        if (!r.rate.isFinite()) p += "reward term '${r.name}' has a non-finite rate of ${r.rate}"
        if (r.source.name.isBlank()) p += "reward term '${r.name}' names a blank source"
    }


    if (episode.maxEpochs <= 0) {
        p += "maxEpochs must be positive; it is ${episode.maxEpochs}. A cap of zero ends the " +
            "episode before any decision is taken, so the run reports nothing and says nothing " +
            "went wrong"
    }

    return p
}
