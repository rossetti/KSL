package ksl.modeling.decision

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 *  Appendix E.2 is the implementer's checklist: every type, its package, and the milestone that
 *  carries it. **This test is that inventory**, and E.2's tables are generated from it.
 *
 *  It exists because E.2 drifted and Appendix G did not, and the difference is not diligence.
 *  Appendix G's listings are copied from the tree on every revision and mechanically verified as
 *  exact slices of it, so a listing cannot silently disagree with the code. E.2 was maintained by
 *  hand, and by the time anyone checked it had seven types in the wrong package — including six
 *  placed in `…decision.descriptor` that reference `DecisionElement` and would therefore fail
 *  [PackageLayeringTest] if anyone had put them where the inventory said.
 *
 *  Two assertions, and the second is the one worth having:
 *
 *   1. **Nothing declared is missing.** Every type named below exists as a top-level declaration
 *      in the package named beside it.
 *   2. **Nothing exists that is not declared.** Every top-level type in the three packages appears
 *      below. A new type cannot reach the tree without being added to the inventory, so the
 *      inventory cannot fall behind the way a hand-maintained table does.
 *
 *  The second is D.24's argument applied to the document rather than to the library: an absent
 *  entry is invisible and a surplus one is auditable, so the design's job is to convert the first
 *  into the second.
 */
class ImplementationInventoryTest {

    /** A type the design commits to: where it lives, and which milestone carries it. */
    private data class Entry(val pkg: String, val type: String, val milestone: Int)

    private infix fun String.m(milestone: Int) = milestone to this

    private fun inventory(): List<Entry> {
        val out = mutableListOf<Entry>()
        fun pkg(name: String, vararg types: Pair<Int, String>) {
            types.forEach { (m, t) -> out += Entry(name, t, m) }
        }

        // ---- ksl.modeling.decision — the element, its DSL, and everything that writes a model.
        pkg(
            "ksl.modeling.decision",
            "DecisionElement" m 1,
            "DecisionElementBuilder" m 1,
            "KSLDecisionDsl" m 1,
            "DecisionCatalog" m 1,
            "LeverRef" m 1,
            "RewardRef" m 2,
            "LeverInfo" m 1,
            "LeverActuator" m 1,
            "StatefulLeverActuator" m 1,
            "BatchLeverActuator" m 1,
            "Neutral" m 1,
            "UnitCoverage" m 1,
            "ObservationDecl" m 1,
            "LeverDecl" m 1,
            "ActionBinding" m 1,
            "DefaultActionBinding" m 1,
            "PreparedAction" m 1,
            "ActionPlan" m 1,
            "PolicyIfc" m 1,
            "ShapeAwarePolicyIfc" m 1,
            "DecisionContext" m 1,
            "MutableDecisionContext" m 1,
            "EpochContext" m 1,
            "ManagedPolicyIfc" m 1,
            "NeutralPolicy" m 1,
            "FixedPolicy" m 1,
            "RewardSourceCIfc" m 1,
            "RewardDecl" m 1,
            "RewardBinding" m 1,
            // The feasible set and the score-and-pick skeleton (§4.4.6, §4.5.5). These are in
            // THIS package and not in `descriptor`: `ElementActionSet` reads `DecisionElement`
            // and delegates membership to `DefaultActionBinding.prepare`, so placing them in
            // the descriptor package would invert E.1's layering.
            "ActionSet" m 1,
            "ElementActionSet" m 1,
            "ActionSearch" m 1,
            "ExhaustiveSearch" m 1,
            "GridSearch" m 1,
            "SampledSearch" m 1,
            "ValueApproximationIfc" m 1,
            "LearnableValueApproximationIfc" m 2,
            "LookaheadPolicy" m 1,
            // The capture CONTRACT lives with its producer; only implementations live in
            // ksl.sdm.capture (E.1, D.19). M1 since §7.1.1: §4.10.2 step 4 emits, and §4.10.4's
            // acceptance matrix asserts what it emits, so M1 cannot be tested without them.
            // `RunProvenance` stays M3 — it is wiring for durable sinks, not for the loop.
            "TransitionRecord" m 1,
            "RunProvenance" m 1,
            "TransitionSink" m 1,
            // Exceptions (E.3).
            "ActionValidationException" m 1,
            "ActionApplicationException" m 1,
            "BindingException" m 1,
            "AmbiguousLeverException" m 1,
            "NarrowingException" m 1,
            "RewardKindException" m 1,
            "NotDeclarableYetException" m 1,
            "StaleDecisionContextException" m 1
        )

        // ---- ksl.modeling.decision.descriptor — plain serializable data. Nothing here may
        // reference the simulation, which is what PackageLayeringTest asserts.
        pkg(
            "ksl.modeling.decision.descriptor",
            "DecisionSurfaceDescriptor" m 1,
            "SchemaVersion" m 1,
            "SchemaVersionException" m 1,
            "ObservationDescriptor" m 1,
            "LeverDescriptor" m 1,
            "LeverDomain" m 1,
            "LeverKind" m 1,
            "JointConstraint" m 1,
            "SumEquals" m 1,
            "SumAtMost" m 1,
            "EpochKind" m 1,
            "EpochDescriptor" m 1,
            "EpisodeDescriptor" m 1,
            "FeasibilityPolicy" m 1,
            "WarmUpOrdering" m 1,
            "TerminationSource" m 1,
            "SourceRef" m 1,
            "ResponseRef" m 1,
            "CounterRef" m 1,
            "RewardDescriptor" m 1,
            "RewardKind" m 1,
            "RewardSense" m 1
        )

        // ---- ksl.sdm.capture — implementations only.
        pkg(
            "ksl.sdm.capture",
            "NullSink" m 1,
            "MemorySink" m 1,
            // Durable capture: the sink, the reader that pairs a trajectory with its provenance,
            // and the row it hands back. The reader lives beside the writer deliberately — the
            // file format is one contract and splitting it across packages invites them to drift.
            "TabularSink" m 2,
            "TrajectoryFile" m 2,
            "StoredTransition" m 2
        )
        return out
    }

    private fun sourceRoot(): File =
        listOf("src/main/kotlin", "KSLCore/src/main/kotlin")
            .map(::File).firstOrNull { it.isDirectory }
            ?: fail("cannot locate the Kotlin source root from ${File(".").absolutePath}")

    /**
     *  Top-level declarations only. A nested type — `Neutral.Current`, `PreparedAction.Ready` —
     *  is part of its owner's contract rather than an entry of its own, and the column-0 match is
     *  what distinguishes them.
     */
    private val declaration = Regex(
        """^(?:internal\s+|public\s+|private\s+)?(?:expect\s+|actual\s+)?""" +
            """(?:data\s+|sealed\s+|abstract\s+|open\s+|value\s+|fun\s+)*""" +
            """(?:class|interface|object|enum\s+class|annotation\s+class)\s+([A-Za-z0-9_]+)"""
    )

    private fun declaredIn(pkg: String): Set<String> {
        val dir = File(sourceRoot(), pkg.replace('.', '/'))
        assertTrue(dir.isDirectory, "expected package directory for $pkg at ${dir.absolutePath}")
        return dir.listFiles { f: File -> f.isFile && f.extension == "kt" }
            .orEmpty()
            .flatMap { f -> f.readLines().mapNotNull { declaration.find(it)?.groupValues?.get(1) } }
            .toSet()
    }

    @Test
    fun everyTypeTheInventoryNamesExistsInThePackageItNames() {
        val missing = mutableListOf<String>()
        val byPackage = inventory().groupBy { it.pkg }
        for ((pkg, entries) in byPackage) {
            val found = declaredIn(pkg)
            for (e in entries) if (e.type !in found) missing += "${e.pkg}.${e.type}"
        }
        println()
        println("Appendix E.2: ${inventory().size} types across ${byPackage.size} packages")
        assertTrue(missing.isEmpty(),
            "the inventory names types that do not exist, or that exist in a different package " +
                "than it claims: $missing")
    }

    @Test
    fun everyTypeInThePackagesAppearsInTheInventory() {
        val declared = inventory().map { "${it.pkg}.${it.type}" }.toSet()
        val surplus = mutableListOf<String>()
        for (pkg in inventory().map { it.pkg }.distinct()) {
            for (t in declaredIn(pkg)) if ("$pkg.$t" !in declared) surplus += "$pkg.$t"
        }
        println()
        println("Types in the tree but not in Appendix E.2: ${surplus.size}")
        surplus.forEach { println("  $it") }
        assertTrue(surplus.isEmpty(),
            "these types exist and the implementation inventory does not list them. Add them to " +
                "this test and regenerate E.2 — a checklist that a new type can be added behind " +
                "is the one that drifted: $surplus")
    }

    /**
     *  A type name that already means something else in KSL is a collision waiting to become an
     *  import alias in user code, and this design walked into one: `ValueFunctionIfc` was declared
     *  in `ksl.modeling.decision` while `ksl.utilities.moda.ValueFunctionIfc` already existed —
     *  both `fun interface`s with a `value(...)` method, and both plausibly imported by a study
     *  scoring decision policies on several criteria.
     *
     *  §5 commits this work to reusing what KSL has, and knowing what KSL has is the same check.
     *  Here it runs.
     */
    @Test
    fun noTypeNameCollidesWithAnExistingKslTypeElsewhere() {
        val ours = inventory().map { it.pkg to it.type }
        val ourPackages = ours.map { it.first }.toSet()
        val elsewhere = mutableMapOf<String, MutableList<String>>()

        sourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                val pkg = f.readLines().firstOrNull { it.startsWith("package ") }
                    ?.removePrefix("package ")?.trim() ?: return@forEach
                if (pkg in ourPackages) return@forEach
                for (line in f.readLines()) {
                    val name = declaration.find(line)?.groupValues?.get(1) ?: continue
                    elsewhere.getOrPut(name) { mutableListOf() } += pkg
                }
            }

        val collisions = ours.mapNotNull { (pkg, type) ->
            elsewhere[type]?.distinct()?.let { "$pkg.$type collides with ${it.joinToString()}.$type" }
        }
        println()
        println("Name collisions between the decision packages and the rest of KSL: ${collisions.size}")
        collisions.forEach { println("  $it") }
        assertTrue(collisions.isEmpty(),
            "a type here shares a simple name with an existing KSL type, so any file using both " +
                "needs an import alias: $collisions")
    }
}
