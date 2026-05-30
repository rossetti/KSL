/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.app.notification

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 *  Substrate contract tests for [NotificationSink] — the host-agnostic
 *  notification sink hoisted in Phase D-Common-3 and adjusted in Phase
 *  E.1.1 (the `Collecting` access-path fix).  Covers:
 *
 *  - `NOOP` silently discards; remains accessible as a singleton.
 *  - `Collecting.emit(spec)` accumulates into the spec list.
 *  - `specs()` returns an immutable snapshot decoupled from later
 *    emits.
 *  - `clear()` resets the history.
 *  - The default convenience methods `info` / `warn` / `error`
 *    construct a [NotificationSpec] with the matching severity and
 *    the supplied message.
 *  - `Collecting` is thread-safe — concurrent emits across N threads
 *    produce a final history matching the total emission count with
 *    no `ConcurrentModificationException`.
 *
 *  Backfilled in Phase E.3 — substrate API coverage for hoisted types.
 */
class NotificationSinkTest {

    // ── NOOP behaviour ────────────────────────────────────────────────────

    @Test
    fun `NOOP emit silently discards a spec`() {
        // Should not throw, should not affect any observable state.
        NotificationSink.NOOP.emit(
            NotificationSpec(message = "hello", severity = NotificationSeverity.INFO)
        )
        NotificationSink.NOOP.emit(
            NotificationSpec(message = "warn", severity = NotificationSeverity.WARNING)
        )
    }

    @Test
    fun `NOOP convenience methods are silent`() {
        NotificationSink.NOOP.info("hello")
        NotificationSink.NOOP.warn("warn")
        NotificationSink.NOOP.error("oops")
        // The contract is purely "no exception" — there is no
        // observable state to inspect.  The assertion is the
        // absence of failure.
    }

    @Test
    fun `NOOP is a shared singleton`() {
        // Two accesses return the same instance reference.
        assertSame(NotificationSink.NOOP, NotificationSink.NOOP,
            "NOOP must be the same instance across accesses (it is a singleton val).")
    }

    // ── Collecting accumulation ──────────────────────────────────────────

    @Test
    fun `fresh Collecting sink starts with an empty spec history`() {
        val sink = NotificationSink.Collecting()
        assertEquals(emptyList(), sink.specs(),
            "A freshly-constructed Collecting must start empty.")
    }

    @Test
    fun `Collecting emit accumulates specs into specs() in emission order`() {
        val sink = NotificationSink.Collecting()
        val s1 = NotificationSpec("first", NotificationSeverity.INFO)
        val s2 = NotificationSpec("second", NotificationSeverity.WARNING)
        val s3 = NotificationSpec("third", NotificationSeverity.ERROR)
        sink.emit(s1)
        sink.emit(s2)
        sink.emit(s3)
        assertEquals(listOf(s1, s2, s3), sink.specs(),
            "Collecting.specs() must return the emitted specs in commit order.")
    }

    @Test
    fun `Collecting specs() returns a snapshot decoupled from later emits`() {
        val sink = NotificationSink.Collecting()
        sink.info("first")
        val snapshot = sink.specs()
        sink.info("second")
        sink.info("third")
        // The snapshot taken before the second/third emits must
        // still carry exactly one spec — it is a list copy, not a
        // live view of the underlying buffer.
        assertEquals(1, snapshot.size,
            "Earlier snapshot must not grow when later emits happen.")
        assertEquals("first", snapshot.single().message)
        assertEquals(3, sink.specs().size,
            "A fresh snapshot must reflect every emit committed so far.")
    }

    @Test
    fun `Collecting clear resets the history`() {
        val sink = NotificationSink.Collecting()
        sink.info("a")
        sink.warn("b")
        sink.error("c")
        assertEquals(3, sink.specs().size)
        sink.clear()
        assertEquals(emptyList(), sink.specs(),
            "After clear(), specs() must report an empty history.")
        // Subsequent emits still work; clear is not destructive to the sink.
        sink.info("after-clear")
        assertEquals(1, sink.specs().size)
        assertEquals("after-clear", sink.specs().single().message)
    }

    // ── Default convenience methods ──────────────────────────────────────

    @Test
    fun `info convenience method builds an INFO-severity spec`() {
        val sink = NotificationSink.Collecting()
        sink.info("hello")
        val spec = sink.specs().single()
        assertEquals(NotificationSeverity.INFO, spec.severity)
        assertEquals("hello", spec.message)
    }

    @Test
    fun `warn convenience method builds a WARNING-severity spec`() {
        val sink = NotificationSink.Collecting()
        sink.warn("careful")
        val spec = sink.specs().single()
        assertEquals(NotificationSeverity.WARNING, spec.severity)
        assertEquals("careful", spec.message)
    }

    @Test
    fun `error convenience method builds an ERROR-severity spec`() {
        val sink = NotificationSink.Collecting()
        sink.error("kaboom")
        val spec = sink.specs().single()
        assertEquals(NotificationSeverity.ERROR, spec.severity)
        assertEquals("kaboom", spec.message)
    }

    // ── Thread safety ────────────────────────────────────────────────────

    @Test
    fun `Collecting tolerates concurrent emits from many threads`() {
        // 8 threads × 250 emits = 2000 emits.  After all threads
        // join, specs().size must equal 2000 with no
        // ConcurrentModificationException and no lost writes.
        val sink = NotificationSink.Collecting()
        val threadCount = 8
        val perThread = 250
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

        for (t in 0 until threadCount) {
            Thread {
                try {
                    startLatch.await()
                    repeat(perThread) { i ->
                        sink.emit(NotificationSpec("t$t-$i", NotificationSeverity.INFO))
                    }
                } catch (ex: Throwable) {
                    errors.add(ex)
                } finally {
                    doneLatch.countDown()
                }
            }.start()
        }
        // Release all threads at once for maximum contention.
        startLatch.countDown()
        val finished = doneLatch.await(30, TimeUnit.SECONDS)
        assertTrue(finished, "All emit threads must finish within the timeout.")
        assertEquals(emptyList(), errors,
            "No emit thread must throw; observed: $errors")
        assertEquals(threadCount * perThread, sink.specs().size,
            "Collecting must record every concurrent emit exactly once.")
    }

    @Test
    fun `Collecting tolerates concurrent emit and snapshot reads`() {
        // 4 writer threads + 2 reader threads.  Readers call specs()
        // in a tight loop while writers emit; readers must never see
        // a ConcurrentModificationException because specs() returns
        // a defensive copy under the same lock as emit().
        val sink = NotificationSink.Collecting()
        val writerCount = 4
        val perWriter = 200
        val readerCount = 2
        val startLatch = CountDownLatch(1)
        val writerDone = CountDownLatch(writerCount)
        val readerDone = CountDownLatch(readerCount)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val stopReaders = AtomicBoolean(false)

        for (w in 0 until writerCount) {
            Thread {
                try {
                    startLatch.await()
                    repeat(perWriter) { i ->
                        sink.emit(NotificationSpec("w$w-$i", NotificationSeverity.INFO))
                    }
                } catch (ex: Throwable) {
                    errors.add(ex)
                } finally {
                    writerDone.countDown()
                }
            }.start()
        }
        for (r in 0 until readerCount) {
            Thread {
                try {
                    startLatch.await()
                    while (!stopReaders.get()) {
                        // Touch the snapshot — any concurrent-mod
                        // bug surfaces here.
                        sink.specs().size
                    }
                } catch (ex: Throwable) {
                    errors.add(ex)
                } finally {
                    readerDone.countDown()
                }
            }.start()
        }
        startLatch.countDown()
        val writersOk = writerDone.await(30, TimeUnit.SECONDS)
        stopReaders.set(true)
        val readersOk = readerDone.await(30, TimeUnit.SECONDS)
        assertTrue(writersOk, "All writer threads must finish within the timeout.")
        assertTrue(readersOk, "All reader threads must finish within the timeout.")
        assertEquals(emptyList(), errors,
            "No writer or reader thread must throw; observed: $errors")
        assertEquals(writerCount * perWriter, sink.specs().size,
            "Final history size must match the total writes.")
    }
}
