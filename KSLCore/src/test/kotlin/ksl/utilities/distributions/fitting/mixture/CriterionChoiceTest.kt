package ksl.utilities.distributions.fitting.mixture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  A preference at the smallest count fitted read the same whether the criterion had turned upward
 *  or was still falling at the top of the range. On 10,000 production lead times searched over one to
 *  six components, BIC preferred one component while still improving at six, and carried to twelve it
 *  crossed below the one-component value at ten: the range decided the answer, and nothing said so.
 */
class CriterionChoiceTest {

    /** BIC on the production lead-time sample, k = 1..6, from the issue report. */
    private val leadTimeBic = mapOf(
        1 to 104158.1, 2 to 104612.0, 3 to 104646.1, 4 to 104576.3, 5 to 104472.1, 6 to 104334.8
    )

    @Test
    fun aLowerMinimumStillFallingAtTheTopIsUnfinished() {
        val c = CriterionChoice.classify("BIC", leadTimeBic, leadTimeBic.keys, smallerIsBetter = true)
        assertEquals(1, c.numComponents)
        assertEquals(ComponentCountBoundary.LOWER, c.boundary)
        assertTrue(c.stillImprovingAtTop)
        assertTrue(c.isUnfinished)
    }

    @Test
    fun aLowerMinimumThatTurnedUpwardIsAnAnswer() {
        val profile = mapOf(1 to 100.0, 2 to 110.0, 3 to 120.0, 4 to 125.0)
        val c = CriterionChoice.classify("BIC", profile, profile.keys, smallerIsBetter = true)
        assertEquals(ComponentCountBoundary.LOWER, c.boundary)
        assertFalse(c.stillImprovingAtTop)
        assertFalse(c.isUnfinished)
    }

    @Test
    fun interiorAndUpperMinima() {
        val interior = mapOf(1 to 120.0, 2 to 100.0, 3 to 110.0)
        assertEquals(
            ComponentCountBoundary.INTERIOR,
            CriterionChoice.classify("AIC", interior, interior.keys, true).boundary
        )
        assertFalse(CriterionChoice.classify("AIC", interior, interior.keys, true).isUnfinished)
        val upper = mapOf(1 to 120.0, 2 to 110.0, 3 to 100.0)
        val c = CriterionChoice.classify("AIC", upper, upper.keys, true)
        assertEquals(ComponentCountBoundary.UPPER, c.boundary)
        assertTrue(c.isUnfinished)
    }

    @Test
    fun largerIsBetterCriteriaUseTheirOwnDirection() {
        val heldOut = mapOf(1 to -3.0, 2 to -2.9, 3 to -2.8)
        val c = CriterionChoice.classify("HeldOut", heldOut, heldOut.keys, smallerIsBetter = false)
        assertEquals(3, c.numComponents)
        assertEquals(ComponentCountBoundary.UPPER, c.boundary)
        assertTrue(c.stillImprovingAtTop)
    }

    @Test
    fun nothingComparableIsNone() {
        val c = CriterionChoice.classify("BIC", emptyMap(), listOf(1, 2, 3), true)
        assertNull(c.numComponents)
        assertEquals(ComponentCountBoundary.NONE, c.boundary)
        assertFalse(c.isUnfinished)
    }

    @Test
    fun everyOutputWarnsExactlyWhenTheSearchIsUnfinished() {
        val data = receptionDeskData()
        for (range in listOf(1..2, 1..4)) {
            val results = MixtureModeler(data).fit(numComponentsRange = range)
            val unfinished = results.isSearchUnfinished()
            val summary = results.criterionSummary()
            val explanation = results.componentCountEvidence().explain()
            assertEquals(unfinished, summary.contains("Unfinished search"), "summary, range $range:\n$summary")
            assertEquals(unfinished, explanation.contains("Unfinished search"), "explain, range $range:\n$explanation")
            assertEquals(unfinished, results.componentCountEvidence().isSearchUnfinished)
        }
        // A range of one to two on this sample leaves at least one criterion at the top.
        assertTrue(MixtureModeler(data).fit(numComponentsRange = 1..2).isSearchUnfinished())
    }

    @Test
    fun aSpecifiedCountIsNeverUnfinished() {
        val results = MixtureModeler(receptionDeskData()).fit(numComponents = 3)
        assertTrue(results.unfinishedCriteria().isEmpty())
        assertFalse(results.criterionSummary().contains("Unfinished search"))
        assertFalse(results.componentCountEvidence().explain().contains("Unfinished search"))
    }
}
