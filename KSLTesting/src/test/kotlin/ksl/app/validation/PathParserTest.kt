package ksl.app.validation

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PathParserTest {

    // ── parse ───────────────────────────────────────────────────────────────

    @Test fun `empty path produces empty segment list`() {
        assertEquals(emptyList(), PathParser.parse(""))
    }

    @Test fun `single name produces one Name segment`() {
        assertEquals(listOf(PathSegment.Name("scenarios")), PathParser.parse("scenarios"))
    }

    @Test fun `dotted names produce Name segments in order`() {
        assertEquals(
            listOf(PathSegment.Name("a"), PathSegment.Name("b"), PathSegment.Name("c")),
            PathParser.parse("a.b.c")
        )
    }

    @Test fun `index brackets produce Index segments`() {
        assertEquals(
            listOf(
                PathSegment.Name("scenarios"),
                PathSegment.Index(3),
                PathSegment.Name("runOverrides"),
                PathSegment.Name("lengthOfReplication")
            ),
            PathParser.parse("scenarios[3].runOverrides.lengthOfReplication")
        )
    }

    @Test fun `consecutive index brackets parse independently`() {
        assertEquals(
            listOf(
                PathSegment.Name("bundleRefs"),
                PathSegment.Index(1),
                PathSegment.Name("paths"),
                PathSegment.Index(0)
            ),
            PathParser.parse("bundleRefs[1].paths[0]")
        )
    }

    @Test fun `non-numeric bracket contents are treated as literal name`() {
        val segs = PathParser.parse("a[foo].b")
        assertEquals(
            listOf(PathSegment.Name("a"), PathSegment.Name("[foo]"), PathSegment.Name("b")),
            segs
        )
    }

    @Test fun `unclosed bracket is absorbed as literal`() {
        val segs = PathParser.parse("a[3.b")
        assertEquals(listOf(PathSegment.Name("a"), PathSegment.Name("[3.b")), segs)
    }

    // ── isAtOrBelow ─────────────────────────────────────────────────────────

    @Test fun `empty prefix matches every candidate`() {
        assertTrue(PathParser.isAtOrBelow("", "scenarios[3].x"))
        assertTrue(PathParser.isAtOrBelow("", ""))
    }

    @Test fun `prefix matches itself`() {
        assertTrue(PathParser.isAtOrBelow("scenarios[3]", "scenarios[3]"))
    }

    @Test fun `prefix matches a descendant`() {
        assertTrue(PathParser.isAtOrBelow("scenarios[3]", "scenarios[3].runOverrides.lengthOfReplication"))
    }

    @Test fun `prefix at name boundary matches an indexed descendant`() {
        assertTrue(PathParser.isAtOrBelow("scenarios", "scenarios[3].x"))
    }

    @Test fun `prefix does not match a string-prefix that is not a segment prefix`() {
        // The dangerous case: scenarios[3] should NOT match scenarios[30].
        assertFalse(PathParser.isAtOrBelow("scenarios[3]", "scenarios[30].x"))
    }

    @Test fun `prefix longer than candidate returns false`() {
        assertFalse(PathParser.isAtOrBelow("scenarios[3].x", "scenarios[3]"))
    }

    @Test fun `unrelated prefix returns false`() {
        assertFalse(PathParser.isAtOrBelow("bundleRefs[0]", "scenarios[0].x"))
    }
}
