package ksl.modeling.fleet

import ksl.modeling.entity.KSLProcessBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.valueParameters
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  The shape of the entity-facing surface, checked against the sibling subsystem's.
 *
 *  Two properties, both of which are easy to lose by accident and neither of which any behavioural
 *  test would notice.
 *
 *  **The verbs are members of `KSLProcessBuilder`.** That is the entity package's idiom -- `seize`,
 *  `delay`, `hold`, `requestConveyor` and the passive `guidedTransport` are all members -- and it
 *  is what lets a process call them with no import beyond the system it is using. Reflection over
 *  the interface's declared functions is the check, because an extension function would not appear
 *  there at all.
 *
 *  **The shared parameters are named the same as the passive subsystem's.** A modeller who knows
 *  one should be able to read the other, and a modeller comparing the two paradigms on one model
 *  should not have to rename arguments to do it. Kotlin's named arguments make a rename a source
 *  break for anyone who used them, so this is worth pinning rather than trusting to care.
 */
class ApiKinshipTest {

    private val builderFunctions = KSLProcessBuilder::class.declaredFunctions

    @Test
    @DisplayName("The three verbs are members of KSLProcessBuilder, not extensions")
    fun theVerbsAreMembers() {
        for (verb in listOf("transportByFleet", "requestFleetTransport", "awaitFleetTransport")) {
            assertNotNull(
                builderFunctions.firstOrNull { it.name == verb },
                "$verb is not a declared member of KSLProcessBuilder. An extension function would " +
                        "also fail this check, which is the point: the entity package's idiom is a " +
                        "member, and an extension would need importing at every call site."
            )
        }
    }

    @Test
    @DisplayName("Shared parameter names match the passive subsystem's guidedTransport")
    fun parameterNamesMatchTheSibling() {
        val passive = assertNotNull(builderFunctions.firstOrNull { it.name == "guidedTransport" })
            .valueParameters.mapNotNull { it.name }.toSet()
        val active = assertNotNull(builderFunctions.firstOrNull { it.name == "transportByFleet" })
            .valueParameters.mapNotNull { it.name }.toSet()

        // Everything the two subsystems both have a concept for is spelled the same way.
        val shared = setOf(
            "destination", "loadingDelay", "unLoadingDelay", "suspensionName"
        )
        for (p in shared) {
            assertTrue(p in passive, "guidedTransport no longer has a parameter named '$p'")
            assertTrue(p in active, "transportByFleet should name its parameter '$p', as guidedTransport does")
        }

        // And where they legitimately differ, they differ for a reason: the passive verb takes a
        // pool and a pickup location because the entity drives the protocol; the active one takes a
        // system and an origin because it does not choose a vehicle at all.
        assertTrue("pool" in passive && "pool" !in active)
        assertTrue("system" in active && "system" !in passive)
        assertTrue("origin" in active, "transportByFleet should call the collection point 'origin'")
    }

    @Test
    @DisplayName("The only KSLProcessBuilder extension in the package is the policy seam")
    fun theAgvPackageDeclaresNoLooseBuilderExtensions() {
        // A source scan, not reflection: a *private* extension is invisible to reflection, and a
        // private extension is exactly the shape this rule exists to prevent.
        //
        // The rule is not "no extensions at all", which is what an earlier draft of the design said.
        // KSLProcessBuilder is annotated @RestrictsSuspension, so a process body may only invoke
        // suspending functions that are members or extensions OF THE BUILDER. A policy interface
        // whose method is a plain `suspend fun assign(context)` therefore cannot be called from the
        // dispatcher's process at all -- it does not compile. Declaring it as a member extension is
        // the only form the language permits, so the two seams that must suspend take that form:
        //
        //   assign   -- the policy seam itself, called as `with(policy) { assign(context) }`
        //   auction  -- DispatchContext's Contract-Net negotiation, which must call `contractNet`,
        //               itself a suspending extension on the builder, and whose deadline is a
        //               suspension a policy is meant to see it is paying for.
        //   handle   -- the interruption seam, called as `with(policy) { handle(interruption) }`.
        //               A breakdown procedure is a wait for a technician, a walk, a look and a
        //               push, so it must be able to consume simulated time for the same reason
        //               `assign` must.
        //   perform  -- the stop action seam, called as `with(stop.action) { perform(visit) }`.
        //               What a vehicle does on arriving is a dwell, a boarding, a wait for a
        //               scheduled departure, or nothing; every one of those but the last takes
        //               simulated time, so a seam that could not suspend could not express the
        //               thing it exists for.
        //   instruct -- the stop control seam, called as
        //               `with(vehicle.stopControl) { instruct(vehicle, stop, tour) }`. Asking a
        //               controller whether to serve the next stop may be a radio call or a
        //               decision epoch, so it must be able to consume simulated time; one that
        //               answers immediately reduces to a synchronous rule and costs nothing.
        //   holdAt   -- StopContextIfc's other verb: a vehicle waiting at a stop for somebody to
        //               turn up. It suspends for the obvious reason, and it is a verb rather than a
        //               delay because the wait ends either at a deadline or at an arrival, and only
        //               the stop can see an arrival.
        //   takeAboard, setDown
        //            -- the two verbs an action is given to move a load, on StopContextIfc. They
        //               suspend because loading and unloading take time, and they are here rather
        //               than inside the actions because every per-load interval this subsystem
        //               reports is recorded in them: an action written outside this library gets
        //               the statistics by calling them, and gets none by not.
        //
        // The verbs a *modeller* writes in their own process -- `transportByFleet`, `tow`, `charge`
        // -- are top-level extensions and live in KSLProcess.kt with the rest of the process API,
        // which is where a reader looks for them and why none of them appears in this scan.
        //
        // Both are members of a type, so neither can be called without naming that type at the call
        // site; neither hides a suspension behind a bare helper name.
        //
        // What stays banned is the loose kind: a top-level or private helper that hides a
        // suspension point behind a name, which is what makes a control loop unreadable.
        // Both halves of the subsystem: the fleet layer and its guide-path binding. The rule is
        // about the subsystem rather than about a directory, so a seam that moved between the two
        // when the packages split is still covered.
        val roots = listOf(
            File("src/main/kotlin/ksl/modeling/fleet"),
            File("src/main/kotlin/ksl/modeling/agv")
        )
        for (root in roots) {
            assertTrue(root.isDirectory, "expected to scan ${root.absolutePath}")
        }

        val declaration = Regex("""(?m)^(\s*)(?:[\w@\s]*?\b)?fun\s+KSLProcessBuilder\s*\.\s*(\w+)""")
        val found = mutableListOf<Triple<String, String, Boolean>>()   // file, name, isTopLevel
        for (file in roots.asSequence().flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }) {
            val source = stripComments(file.readText())
            for (m in declaration.findAll(source)) {
                found.add(Triple(file.name, m.groupValues[2], m.groupValues[1].isEmpty()))
            }
        }

        val loose = found.filter { it.third }
        assertEquals(emptyList(), loose,
            "top-level extensions on KSLProcessBuilder found. Suspensions in this package's control " +
                    "loops are written at the call site so a reader of a loop can see every point at " +
                    "which simulated time passes.")

        assertEquals(
            setOf(
                "assign", "auction", "handle", "perform", "takeAboard", "setDown", "holdAt",
                "instruct"
            ),
            found.map { it.second }.toSet(),
            "the only permitted KSLProcessBuilder extensions in this package are the seams that " +
                    "@RestrictsSuspension forces into that form -- the assignment policy, the " +
                    "Contract-Net auction, the interruption policy, the stop action, the stop " +
                    "control, and the verbs an action is given. A new name here is a new " +
                    "place a suspension can hide, and should be justified before it is added to " +
                    "this list. Found: $found"
        )
    }

    /** Crude but sufficient: the scan must not be fooled by a comment that quotes the pattern. */
    private fun stripComments(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(?m)//.*$"""), "")
}
