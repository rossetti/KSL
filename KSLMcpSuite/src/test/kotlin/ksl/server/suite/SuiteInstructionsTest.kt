package ksl.server.suite

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * A tripwire on the connect-time `instructions`, which are the only guidance that reaches EVERY MCP
 * client before its first tool call — tool descriptions arrive later, or piecemeal, or not at all.
 *
 * Each assertion below corresponds to a mistake observed in practice on 2026-08-13, when routing was
 * followed correctly and the authoring underneath it still went wrong. They are string checks, which
 * is crude, but the failure they guard against is someone trimming the field back to routing-only and
 * nobody noticing until an agent fumbles a config again.
 */
class SuiteInstructionsTest {

    @Test
    @DisplayName("the simulation instructions teach config authoring, not just routing")
    fun simInstructionsCoverAuthoring() {
        val text = SIM_INSTRUCTIONS
        assertTrue("get_started" in text, "should still route the unsure user")
        assertTrue("describe_model" in text, "guessing input keys was an observed failure")
        assertTrue("run_template" in text, "hand-authoring a config was an observed failure")
        assertTrue("validate_run_config" in text, "the validate step is what catches authoring errors")
        assertTrue("rvOverride" in text, "a distribution mean set as a control was an observed failure")
        assertTrue("db_compare" in text, "comparison must route to the batch + MCB path")
        assertTrue("run_model" in text, "the instruction is specifically NOT to repeat run_model")
        assertTrue("get_artifact" in text, "handing back the artifact link was an observed failure")
    }

    @Test
    @DisplayName("the search surfaces keep their search-first, cite-the-URL rules")
    fun searchInstructionsAreIntact() {
        assertTrue("search_textbook" in BOOK_INSTRUCTIONS && "cite" in BOOK_INSTRUCTIONS)
        assertTrue("search_code" in CODE_INSTRUCTIONS && "cite" in CODE_INSTRUCTIONS)
        assertTrue("invent" in CODE_INSTRUCTIONS, "the don't-invent-signatures rule earns its place")
    }

    @Test
    @DisplayName("instructions stay terse — they sit in every request's context")
    fun instructionsStayTerse() {
        // Not a style preference: this field is always in context and is concatenated across all
        // enabled surfaces, so length is a per-request cost on every client. The current text is
        // ~800 characters; the ceiling leaves room to edit without inviting an essay.
        assertTrue(
            SIM_INSTRUCTIONS.length < 1200,
            "SIM_INSTRUCTIONS is ${SIM_INSTRUCTIONS.length} chars — trim it, or move detail into the skill",
        )
    }
}
