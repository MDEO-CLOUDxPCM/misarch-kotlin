package org.misarch.common.timing.counters

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder
import kotlin.concurrent.thread

/** Frames, coroutine binding, SQL normalisation, totals, and the cost of one frame. */
class CpuCountersTest {

    @BeforeEach
    fun on() {
        assertTrue(CpuCounters.enable())
        CpuCounters.reset()
    }

    @AfterEach
    fun off() = CpuCounters.disable()

    private class Acc : Account {
        val ns = LongAdder()
        override fun add(ns: Long) = this.ns.add(ns)
        val ms get() = ns.sum() / 1e6
    }

    /** Burns about [ms] of CPU on the calling thread. */
    private fun burn(ms: Long) {
        val end = CpuCounters.now() + ms * 1_000_000
        var x = 0L
        while (CpuCounters.now() < end) x += x xor 31
        if (x == 42L) println()
    }

    private fun assertMs(expected: Double, actual: Double) =
        assertTrue(actual >= expected * 0.9 && actual <= expected * 1.5 + 3, "expected ~$expected ms, was $actual")

    @Test
    fun `nested frames charge exclusively, the innermost gets the CPU`() {
        val outer = Acc(); val inner = Acc(); val statement = Acc()
        CpuCounters.explicit(outer) {
            burn(20)
            CpuCounters.explicit(inner) {
                burn(30)
                CpuCounters.explicitStatement(statement) { burn(10) }
            }
            burn(20)
        }
        assertMs(40.0, outer.ms); assertMs(30.0, inner.ms); assertMs(10.0, statement.ms)
        // a null account drops its CPU (the counters' own overhead)
        val after = Acc()
        CpuCounters.explicit(after) { CpuCounters.explicitStatement(null) { burn(10) } }
        assertTrue(after.ms < 3, "dropped: ${after.ms}")
    }

    @Test
    fun `coroutines - fetcher coroutine bound to its explicit frame across threads, children inherit, rest to request`() {
        val request = Acc(); val fetcherA = Acc(); val fetcherB = Acc()
        val element = RequestCpuElement(request)
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob() + element)
        val gate = CompletableDeferred<Unit>()
        val gateB = CompletableDeferred<Unit>()
        // like graphql-kotlin: the fetcher wrapper's explicit frame around scope.future (Unconfined: starts in place)
        val a = CpuCounters.explicit(fetcherA) {
            scope.future {
                burn(10)
                gate.await()                 // resumed on another thread
                burn(20)
                async { burn(15) }.await()   // child: inherits A
                burn(5)
            }
        }
        val b = CpuCounters.explicit(fetcherB) {
            scope.future { gateB.await(); burn(25) }
        }
        val root = scope.future { burn(10) } // no explicit frame: the request
        thread { gate.complete(Unit) }.join()
        thread { gateB.complete(Unit) }.join()
        a.get(5, TimeUnit.SECONDS); b.get(5, TimeUnit.SECONDS); root.get(5, TimeUnit.SECONDS)
        assertMs(50.0, fetcherA.ms)
        assertMs(25.0, fetcherB.ms)
        assertMs(10.0, request.ms)
    }

    @Test
    fun `sql normalisation, mode and table`() {
        val n = SqlNormaliser.normalise(
            "SELECT t.* FROM \"productentity\" t WHERE (t.isPubliclyVisible = \$1)\n  ORDER BY id ASC LIMIT 10 OFFSET 200"
        )
        assertEquals("SELECT t.* FROM \"productentity\" t WHERE (t.isPubliclyVisible = ?) ORDER BY id ASC LIMIT ? OFFSET ?", n)
        assertEquals(
            "SELECT * FROM x WHERE id IN (?...) AND name = ? AND s0 = ?::uuid",
            SqlNormaliser.normalise("SELECT * FROM x WHERE id IN (\$1, \$2, \$3) AND name = 'a''b' AND s0 = :p0::uuid"),
        )
        assertEquals("INSERT INTO y (a, b) VALUES (?...), ...", SqlNormaliser.normalise("INSERT INTO y (a, b) VALUES (\$1, \$2), (\$3, \$4)"))
        assertEquals("read", SqlNormaliser.mode("SELECT * FROM x WHERE id = ? FOR UPDATE"))
        assertEquals("write", SqlNormaliser.mode("WITH s0 AS (INSERT INTO orders (id) VALUES (?)) SELECT 1"))
        assertEquals("write", SqlNormaliser.mode("UPDATE \"orderitementity\" SET a = ?"))
        assertEquals("productentity", SqlNormaliser.table("SELECT COUNT(t.id) FROM \"productentity\" t"))
        assertEquals("orderitementity", SqlNormaliser.table("UPDATE \"orderitementity\" SET a = ?"))
        // the same normalised statement shares one counter
        assertTrue(CpuCounters.statement("SELECT * FROM x LIMIT 1 OFFSET 10") === CpuCounters.statement("SELECT * FROM x LIMIT 1 OFFSET 20"))
    }

    @Test
    fun `snapshot schema and the thread total`() {
        CpuCounters.operation("graphql", "Query.x").apply { calls.increment(); add(5) }
        CpuCounters.statement("SELECT 1 FROM t").apply { calls.increment() }
        val s = CpuCounters.snapshot()
        assertEquals(listOf("enabled", "t", "statements", "operations", "threadCpuNsTotal"), s.keys.toList())
        @Suppress("UNCHECKED_CAST")
        val st = (s["statements"] as List<Map<String, Any>>).single()
        assertEquals(listOf("key", "sql", "entity", "mode", "calls", "rows", "driverCpuNs", "mappingCpuNs"), st.keys.toList())
        assertEquals("t", st["entity"]); assertEquals("read", st["mode"])
        @Suppress("UNCHECKED_CAST")
        val op = (s["operations"] as List<Map<String, Any>>).single()
        assertEquals(mapOf("key" to "Query.x", "kind" to "graphql", "calls" to 1L, "cpuNs" to 5L), op)
        val t1 = s["threadCpuNsTotal"] as Long
        burn(5)
        val sampled = java.util.concurrent.CountDownLatch(1)
        val worker = thread { burn(5); sampled.await() }
        while (worker.isAlive && CpuCounters.threadCpuNsTotal() < t1 + 9_000_000) Thread.sleep(1)
        val t2 = CpuCounters.threadCpuNsTotal()
        sampled.countDown(); worker.join()
        // a thread that ended keeps its last sampled CPU in the total: the total never decreases
        val t3 = CpuCounters.threadCpuNsTotal()
        assertTrue(t1 > 0 && t2 >= t1 + 9_000_000 && t3 >= t2, "$t1 -> $t2 -> $t3")
    }

    /** The counters' cost: one frame (enter + exit, two clock reads) and one coroutine resume/suspend. */
    @Test
    fun `cost of a frame`() {
        val acc = Acc()
        repeat(200_000) { CpuCounters.explicit(acc) { } }   // warm-up
        val n = 1_000_000
        val t0 = System.nanoTime()
        repeat(n) { CpuCounters.explicit(acc) { } }
        val perFrame = (System.nanoTime() - t0).toDouble() / n
        val clock0 = System.nanoTime()
        repeat(n) { CpuCounters.now() }
        val perClock = (System.nanoTime() - clock0).toDouble() / n
        val element = RequestCpuElement(acc)
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob() + element)
        val c0 = System.nanoTime()
        runBlocking { repeat(100_000) { scope.future { }.get() } }
        val perCoroutine = (System.nanoTime() - c0).toDouble() / 100_000
        CpuCounters.disable()
        val c1 = System.nanoTime()
        val plain = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        runBlocking { repeat(100_000) { plain.future { }.get() } }
        val perPlainCoroutine = (System.nanoTime() - c1).toDouble() / 100_000
        println("timing counters cost: clock read %.0f ns, frame %.0f ns, coroutine with element %.0f ns, without %.0f ns"
            .format(perClock, perFrame, perCoroutine, perPlainCoroutine))
        assertTrue(perFrame < 20_000, "frame $perFrame ns")
    }
}
