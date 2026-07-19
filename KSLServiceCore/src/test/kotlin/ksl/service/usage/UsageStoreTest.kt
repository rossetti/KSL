package ksl.service.usage

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsageStoreTest {

    @Test
    @DisplayName("OFF records nothing (the opt-out); toggling the level live takes effect immediately")
    fun offLevelRecordsNothing(@TempDir tmp: Path) {
        val store = UsageStore(tmp, UsageLevel.OFF)
        store.recorderFor("sim").record("run_model", 5, true, UsageDetails(target = "MM1"))
        assertEquals(0, store.summary().total)
        assertEquals(0, store.all().size) // nothing written to the file either
        store.level = UsageLevel.FULL // live toggle
        store.recorderFor("sim").record("run_model", 5, true)
        assertEquals(1, store.summary().total)
    }

    @Test
    @DisplayName("COUNTS drops free text (query/paramsDigest/errorSummary) but keeps ids, counts, scores")
    fun countsDropsFreeText(@TempDir tmp: Path) {
        val store = UsageStore(tmp, UsageLevel.COUNTS)
        store.recorderFor("book").record(
            "search_textbook", 10, true,
            UsageDetails(
                query = "event scheduling", target = "sec-x", resultCount = 3, topScore = 4.2,
                errorSummary = "boom", paramsDigest = "reps=30",
            ),
        )
        val e = store.recent(1).first()
        assertNull(e.query)
        assertNull(e.paramsDigest)
        assertNull(e.errorSummary)
        assertEquals("sec-x", e.target)
        assertEquals(3, e.resultCount)
        assertEquals(4.2, e.topScore)
    }

    @Test
    @DisplayName("FULL keeps the raw query text")
    fun fullKeepsQuery(@TempDir tmp: Path) {
        val store = UsageStore(tmp, UsageLevel.FULL)
        store.recorderFor("book").record("search_textbook", 10, true, UsageDetails(query = "M/M/1 queue"))
        assertEquals("M/M/1 queue", store.recent(1).first().query)
    }

    @Test
    @DisplayName("records events and aggregates a summary")
    fun recordsAndSummarizes(@TempDir tmp: Path) {
        val store = UsageStore(tmp)
        store.recorderFor("sim").record("run_model", 120, true)
        store.recorderFor("sim").record("run_model", 90, false)
        store.recorderFor("book").record("search_textbook", 10, true)

        val summary = store.summary()
        assertEquals(3, summary.total)
        assertEquals(2, summary.ok)
        assertEquals(2, summary.byTool["run_model"])
        assertEquals(2, summary.byCapability["sim"])
        assertEquals(1, summary.byCapability["book"])
        assertTrue(summary.successRate in 0.66..0.67, "2/3 success: ${summary.successRate}")
    }

    @Test
    @DisplayName("recent() is the newest-first current-run view; all() is the durable cross-instance log")
    fun recentIsCurrentRunAllIsDurable(@TempDir tmp: Path) {
        UsageStore(tmp).recorderFor("code").record("search_code", 5, true)
        val store = UsageStore(tmp)
        store.recorderFor("code").record("get_class", 7, true)

        // recent() is THIS run only (the in-memory ring), newest first
        val recent = store.recent(10)
        assertEquals(1, recent.size)
        assertEquals("get_class", recent.first().tool)
        // a fresh instance has an empty current-run view (the ring is not seeded from the file)
        assertEquals(0, UsageStore(tmp).recent(10).size)
        // ...but the durable file holds all-time events across instances (the CSV-export path)
        val all = UsageStore(tmp).all()
        assertEquals(listOf("search_code", "get_class"), all.map { it.tool })
    }

    @Test
    @DisplayName("an empty store summarizes cleanly")
    fun emptyStoreSummarizesCleanly(@TempDir tmp: Path) {
        val summary = UsageStore(tmp).summary()
        assertEquals(0, summary.total)
        assertEquals(1.0, summary.successRate)
    }
}
