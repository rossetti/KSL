package ksl.simulation

import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.TWResponse
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A model element's name is stored with any `.` replaced by `_`, because the controls framework keys
 * a control as `elementName.propertyName` and splits on that character.
 *
 * The rewrite was applied on write and not on read, so the round trip did not close: you named
 * something one thing, it was stored as another, and asking for it by the name you wrote returned
 * null — the same answer as for a name that never existed. The failure surfaced far from its cause,
 * usually as a NaN in a results table, and the trigger is ordinary, because a parameter sweep over
 * `Double` values produces exactly such names:
 *
 *     for (tba in listOf(3.0, 4.0, 6.0)) Model("Warehouse_$tba")   // "Warehouse_3.0" -> "Warehouse_3_0"
 *
 * The lookups now canonicalize their argument the same way, so either form finds the element.
 */
class ModelElementNameLookupTest {

    /** A concrete leaf; ModelElement itself is abstract. */
    private class Leaf(parent: ModelElement, label: String) : ModelElement(parent, label)

    /** The reporting scenario in miniature: names built from a swept value, then looked up. */
    private class SweepSubModel(parent: ModelElement, label: String) : ModelElement(parent, label) {
        val timeInSystem = Response(this, "${this.name}:TimeInSystem")
        val delivered = Counter(this, "${this.name}:Delivered")
        val onHand = TWResponse(this, "${this.name}:OnHand")
        val interarrival = RandomVariable(this, ExponentialRV(6.0, 1), "${this.name}:TBA")
    }

    @Test
    @DisplayName("A dotted name is stored with the dot replaced")
    fun aDottedNameIsStoredCanonically() {
        val m = Model("Probe.Model")
        val sub = SweepSubModel(m, "Study1.5")
        assertEquals("Probe_Model", m.name)
        assertEquals("Study1_5", sub.name)
        assertEquals("Study1_5:TimeInSystem", sub.timeInSystem.name)
    }

    /**
     * The defect, stated as the behaviour that replaces it. Every name-based lookup must answer the
     * same for the name that was written and the name that was stored.
     */
    @Test
    @DisplayName("Every name-based lookup accepts the written name and the stored name alike")
    fun lookupsAcceptEitherForm() {
        val m = Model("Probe")
        val sub = SweepSubModel(m, "Study1.5")

        assertSame(sub.timeInSystem, m.response("Study1.5:TimeInSystem"))
        assertSame(sub.timeInSystem, m.response("Study1_5:TimeInSystem"))

        assertSame(sub.delivered, m.counter("Study1.5:Delivered"))
        assertSame(sub.delivered, m.counter("Study1_5:Delivered"))

        assertSame(sub.onHand, m.timeWeightedResponse("Study1.5:OnHand"))
        assertSame(sub.onHand, m.timeWeightedResponse("Study1_5:OnHand"))

        assertSame(sub.interarrival, m.randomVariable("Study1.5:TBA"))
        assertSame(sub.interarrival, m.randomVariable("Study1_5:TBA"))

        assertSame(sub, m.getModelElement("Study1.5"))
        assertSame(sub, m.getModelElement("Study1_5"))

        // containsModelElement answered false for an element that exists, which is the same silent
        // lie the null returns were.
        assertTrue(m.containsModelElement("Study1.5"))
        assertTrue(m.containsModelElement("Study1_5"))
    }

    /**
     * Closing the round trip must not make every miss into a hit. A name that was never used still
     * returns null, and the canonicalization cannot manufacture a match.
     */
    @Test
    @DisplayName("A name that does not exist still returns null in either form")
    fun anAbsentNameIsStillAbsent() {
        val m = Model("Probe")
        SweepSubModel(m, "Study1.5")

        assertNull(m.response("NoSuchThing"))
        assertNull(m.response("No.Such.Thing"))
        assertNull(m.counter("Study1.5:NotACounter"))
        assertNull(m.getModelElement("Study9.9"))
        assertTrue(!m.containsModelElement("Study9.9"))
    }

    /**
     * A lookup still respects the element's type: asking for a counter by a response's name is a
     * miss, not a cast failure, and canonicalizing the query does not change that.
     */
    @Test
    @DisplayName("Canonicalizing the query does not loosen the type check")
    fun theTypeCheckIsUnchanged() {
        val m = Model("Probe")
        val sub = SweepSubModel(m, "Study1.5")
        assertNotNull(m.response("Study1.5:TimeInSystem"))
        assertNull(m.counter("Study1.5:TimeInSystem"))
        assertNull(m.randomVariable("Study1.5:Delivered"))
        assertSame(sub.delivered, m.counter("Study1.5:Delivered"))
    }

    /**
     * Why canonicalizing the query loses no precision. Two names differing only by `.` versus `_`
     * cannot both be in a model: the second is rejected as a duplicate of the first's stored name.
     * So there is no pair for a canonicalized lookup to confuse — the ambiguity it was once thought
     * to introduce is one the model already refuses to create.
     */
    @Test
    @DisplayName("Two names differing only by dot versus underscore cannot both exist")
    fun aDottedNameAndItsUnderscoredTwinCollide() {
        val m = Model("Probe")
        Leaf(m, "Alpha.Beta")
        val error = assertThrows(IllegalArgumentException::class.java) {
            Leaf(m, "Alpha_Beta")
        }
        assertTrue(error.message!!.contains("Alpha_Beta")) { "unexpected message: ${error.message}" }
        assertTrue(error.message!!.contains("unique name")) { "unexpected message: ${error.message}" }
    }

    /** The rule is published, so a caller building an external key can apply it rather than guess. */
    @Test
    @DisplayName("canonicalName is the one definition of the rule")
    fun canonicalNameIsPublic() {
        assertEquals("Study1_5", ModelElement.canonicalName("Study1.5"))
        assertEquals("Warehouse_3_0", ModelElement.canonicalName("Warehouse_3.0"))
        assertEquals("NoDots", ModelElement.canonicalName("NoDots"))
        // And it is the same rule the constructor applies.
        val m = Model("Probe")
        val e = Leaf(m, "A.B.C")
        assertEquals(ModelElement.canonicalName("A.B.C"), e.name)
    }
}
