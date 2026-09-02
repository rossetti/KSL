package ksl.modeling.decision

import org.junit.jupiter.api.DisplayName
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 *  S§C.0 / plan D4 — the library owns no decision timing, asserted rather than promised.
 *
 *  The decoupling's whole claim is that a decision element does not decide *when* it decides. That
 *  claim is easy to state and easy to erode: the next person who wants a periodic review and does
 *  not want to write five lines will add `every(interval)` back, and nothing would notice. A driver
 *  shipped outside KSLCore (`ksl.examples.general.decision.PeriodicReview`, and its twin in
 *  KSLExamples) is what makes the claim structural, and this is what keeps it that way.
 *
 *  Written the way `PackageLayeringTest` is, and for the same reason it is: the compiler cannot see
 *  this property, so a violation builds cleanly and is invisible in review.
 */
class NoTimingConceptTest {

    private fun sourceRoot(): File =
        listOf("src/main/kotlin", "KSLCore/src/main/kotlin")
            .map(::File).firstOrNull { it.isDirectory }
            ?: fail("cannot locate the Kotlin source root from ${File(".").absolutePath}")

    private fun decisionSources(): List<File> =
        File(sourceRoot(), "ksl/modeling/decision").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    @DisplayName("The decision package declares no vocabulary for when a decision happens")
    fun noTimingVocabularySurvives() {
        // Each of these was a way of saying "decide at these times" and each is now the caller's
        // business. `epochPriority` is deliberately absent from the list: it survives, narrowed to
        // ordering deferred epochs against other events at one instant (plan D1).
        val gone = listOf("EpochKind", "EpochDescriptor", "epochInterval", "myEpochInterval",
            "scheduleNextEpoch", "calendarIndex", "firstAtTimeZero", "timingDeclared")
        val offenders = mutableListOf<String>()
        for (f in decisionSources()) {
            for ((n, line) in f.readLines().withIndex()) {
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || code.startsWith("/*")) continue
                for (g in gone) if (Regex("\\b$g\\b").containsMatchIn(code)) {
                    offenders += "${f.name}:${n + 1}  $g"
                }
            }
        }
        assertTrue(offenders.isEmpty(),
            "the decision packages have grown a timing concept again. The element does not own " +
                "when it decides; a caller does, and a driver outside KSLCore provides the common " +
                "case. Found: ${offenders.joinToString("; ")}")
    }

    @Test
    @DisplayName("The element schedules nothing but the epoch a caller deferred")
    fun theOnlyScheduledThingIsTheDeferredEpoch() {
        val sites = mutableListOf<String>()
        for (f in decisionSources()) {
            for ((n, line) in f.readLines().withIndex()) {
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || code.startsWith("/*")) continue
                if (Regex("\\bschedule\\s*\\(").containsMatchIn(code)) sites += "${f.name}:${n + 1}  $code"
            }
        }
        // Every site must be the deferred epoch. There is more than one because draining a request
        // queue opens the drain and re-opens it when a request arrived during one -- but they are the
        // same event, and nothing else is scheduled. A site that is not `deferredAction` means the
        // element has started deciding when something happens again, whatever the surrounding names
        // say.
        assertTrue(sites.isNotEmpty(), "the deferred epoch must still be scheduled somewhere")
        val foreign = sites.filterNot { it.contains("deferredAction") }
        assertTrue(foreign.isEmpty(),
            "the decision package should schedule nothing but the deferred epoch, and it also " +
                "schedules: ${foreign.joinToString("; ")}")
    }
}
