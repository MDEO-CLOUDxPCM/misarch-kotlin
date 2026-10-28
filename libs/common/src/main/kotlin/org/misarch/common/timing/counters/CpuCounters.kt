package org.misarch.common.timing.counters

import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * Precise CPU counters of the timing campaign (docs/TIMING-COUNTERS.md): thread CPU time (`CLOCK_THREAD_CPUTIME_ID`
 * via `ThreadMXBean.getCurrentThreadCpuTime`) attributed exclusively ("to the innermost") to
 * - **operations**: GraphQL data fetchers (`ParentType.field`), `_entities` per type, data loader batches, Dapr event
 *   handlers (by topic) and HTTP handlers (method + route), see [RequestCpuElement] and [TimingCountersConfiguration];
 * - **statements**: per normalised SQL statement, the driver CPU on the calling thread (statement creation, binding,
 *   `execute()` and its subscription) and the mapping CPU (row -> object inside `Result.map`, entity -> parameters in
 *   Spring Data's converter), see [CountingConnectionFactory].
 *
 * Every thread keeps a stack of frames; entering a frame charges the CPU since the last switch to the frame below,
 * leaving charges the frame itself. Operation CPU therefore excludes the statement CPU measured inside it, and the two
 * sums are disjoint. CPU outside any frame (Netty I/O and decoding before a row reaches the mapping function, Reactor
 * operator chains outside coroutines, GC, JIT) is not attributed; [threadCpuNsTotal] gives the total for coverage.
 *
 * Off unless [enable]d (`misarch.timing.counters`, default = `misarch.timing.spans`); while off every hook is one
 * volatile read.
 */
object CpuCounters {

    @Volatile
    @JvmStatic
    var enabled: Boolean = false
        private set

    private val threads = ManagementFactory.getThreadMXBean()

    /** Ignore keys beyond this many operations / statements (they go to an `(other)` entry). */
    private const val MAX_KEYS = 5000

    /** Frames entered so far (for the overhead estimate: frames x cost of a frame; not in the JSON). */
    val framesEntered = LongAdder()

    internal val operations = ConcurrentHashMap<String, OpCounter>()
    internal val statements = ConcurrentHashMap<String, StatementCounter>()

    /** Raw SQL -> its counter (statements are normalised once per distinct text). */
    private val bySql = ConcurrentHashMap<String, StatementCounter>()

    fun enable(): Boolean {
        if (!threads.isCurrentThreadCpuTimeSupported) return false
        if (!threads.isThreadCpuTimeEnabled) threads.isThreadCpuTimeEnabled = true
        enabled = true
        return true
    }

    fun disable() {
        enabled = false
    }

    /** Clears all counters (tests). */
    fun reset() {
        operations.clear(); statements.clear(); framesEntered.reset(); bySql.clear(); threadTotals.clear(); deadThreadsNs = 0
    }

    /** CPU time of the current thread in ns. */
    @JvmStatic
    fun now(): Long = threads.currentThreadCpuTime

    // ------------------------------------------------------------------------------------------------ registry

    fun operation(kind: String, key: String): OpCounter {
        operations[key]?.let { return it }
        if (operations.size >= MAX_KEYS) return operations.computeIfAbsent("(other)") { OpCounter("(other)", kind) }
        return operations.computeIfAbsent(key) { OpCounter(key, kind) }
    }

    /** The counter of [sql] (raw text as given to the driver). */
    fun statement(sql: String): StatementCounter {
        bySql[sql]?.let { return it }
        val normalised = SqlNormaliser.normalise(sql)
        val counter = statements[normalised]
            ?: if (statements.size >= MAX_KEYS) statements.computeIfAbsent("(other)") { StatementCounter("(other)") }
            else statements.computeIfAbsent(normalised) { StatementCounter(it) }
        if (bySql.size < 20_000) bySql[sql] = counter
        return counter
    }

    // ------------------------------------------------------------------------------------------------ frames

    internal class FrameStack {
        var accounts = arrayOfNulls<Account>(32)
        var starts = LongArray(32)
        var kinds = ByteArray(32)
        var depth = 0

        fun push(account: Account?, kind: Byte, now: Long) {
            if (depth == accounts.size) {
                accounts = accounts.copyOf(depth * 2); starts = starts.copyOf(depth * 2); kinds = kinds.copyOf(depth * 2)
            }
            accounts[depth] = account; starts[depth] = now; kinds[depth] = kind
            depth++
        }
    }

    const val EXPLICIT: Byte = 1   // pushed by a synchronous wrapper (fetcher, data loader launch, HTTP filter)
    const val COROUTINE: Byte = 2  // a coroutine resumed with its operation
    const val STATEMENT: Byte = 3  // statement driver or mapping

    private val stacks: ThreadLocal<FrameStack> = ThreadLocal.withInitial { FrameStack() }

    internal fun stack(): FrameStack = stacks.get()

    /** Pushes a frame for [account]; returns the depth before, for [exitTo]. */
    @JvmStatic
    fun enter(account: Account?, kind: Byte): Int {
        val stack = stacks.get()
        val now = now()
        framesEntered.increment()
        val d = stack.depth
        if (d > 0) stack.accounts[d - 1]?.add(now - stack.starts[d - 1])
        stack.push(account, kind, now)
        return d
    }

    /** Pops the frames above [depth], charging each; the frame below continues from now. */
    @JvmStatic
    fun exitTo(depth: Int) {
        val stack = stacks.get()
        if (stack.depth <= depth) return
        val now = now()
        while (stack.depth > depth) {
            val i = stack.depth - 1
            stack.accounts[i]?.add(now - stack.starts[i])
            stack.accounts[i] = null
            stack.depth = i
        }
        if (depth > 0) stack.starts[depth - 1] = now
    }

    /** Runs [block] in an explicit frame of [account] (CPU of the block, minus inner frames, goes to it). */
    inline fun <T> explicit(account: Account, block: () -> T): T {
        val d = enter(account, EXPLICIT)
        try {
            return block()
        } finally {
            exitTo(d)
        }
    }

    /** Runs [block] in a statement frame of [account] (null: the CPU is charged to nobody). */
    inline fun <T> explicitStatement(account: Account?, block: () -> T): T {
        val d = enter(account, STATEMENT)
        try {
            return block()
        } finally {
            exitTo(d)
        }
    }

    /** The account of the top frame if it is an explicit operation frame, else null. */
    internal fun topExplicit(): Account? {
        val stack = stacks.get()
        val d = stack.depth
        return if (d > 0 && stack.kinds[d - 1] == EXPLICIT) stack.accounts[d - 1] else null
    }

    /** The nearest operation frame's account (explicit or coroutine), skipping statement frames. */
    internal fun topOperation(): Account? {
        val stack = stacks.get()
        var i = stack.depth - 1
        while (i >= 0) {
            if (stack.kinds[i] != STATEMENT) return stack.accounts[i]
            i--
        }
        return null
    }

    // ------------------------------------------------------------------------------------------------ write mapping

    /** Spring Data's entity -> parameters conversion on this thread, not yet given to a statement. */
    internal class PendingWrite(var cpuNs: Long = 0, var entity: String? = null)

    private val pendingWrites: ThreadLocal<PendingWrite> = ThreadLocal.withInitial { PendingWrite() }

    internal fun addPendingWrite(cpuNs: Long, entity: String) {
        val p = pendingWrites.get()
        p.cpuNs += cpuNs
        p.entity = entity
    }

    /** Takes the pending write conversion of this thread (consumed by the next write statement created on it). */
    internal fun takePendingWrite(): PendingWrite? {
        val p = pendingWrites.get()
        if (p.entity == null) return null
        val taken = PendingWrite(p.cpuNs, p.entity)
        p.cpuNs = 0; p.entity = null
        return taken
    }

    // ------------------------------------------------------------------------------------------------ totals

    private val threadTotals = HashMap<Long, Long>()
    private var deadThreadsNs = 0L

    /**
     * Sum of the CPU time of all application threads since start (ThreadMXBean). Threads that ended keep their last
     * sampled value, so the sum only grows (the CPU of a thread between its last sample and its end is lost).
     */
    @Synchronized
    fun threadCpuNsTotal(): Long {
        val ids = threads.allThreadIds
        val times = (threads as? com.sun.management.ThreadMXBean)?.getThreadCpuTime(ids)
            ?: LongArray(ids.size) { threads.getThreadCpuTime(ids[it]) }
        val alive = HashSet<Long>(ids.size * 2)
        var sum = 0L
        for (i in ids.indices) {
            val t = times[i]
            if (t < 0) continue
            alive += ids[i]
            threadTotals[ids[i]] = t
            sum += t
        }
        val it = threadTotals.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in alive) {
                deadThreadsNs += e.value; it.remove()
            }
        }
        return sum + deadThreadsNs
    }

    /** The counters as served at `GET /timing/counters` (field order as documented). */
    fun snapshot(): Map<String, Any?> {
        val total = threadCpuNsTotal()
        return linkedMapOf(
            "enabled" to enabled,
            "t" to System.currentTimeMillis(),
            "statements" to statements.values.sortedBy { it.sql }.map { it.toJson() },
            "operations" to operations.values.sortedBy { it.key }.map { it.toJson() },
            "threadCpuNsTotal" to total,
        )
    }
}

/** Something CPU time is charged to. */
fun interface Account {
    fun add(ns: Long)
}

/** One operation (GraphQL field, entity type, data loader, event topic, HTTP route). */
class OpCounter(val key: String, val kind: String) : Account {
    val calls = LongAdder()
    val cpuNs = LongAdder()

    override fun add(ns: Long) = cpuNs.add(ns)

    internal fun toJson(): Map<String, Any> =
        linkedMapOf("key" to key, "kind" to kind, "calls" to calls.sum(), "cpuNs" to cpuNs.sum())
}

/** One normalised SQL statement. */
class StatementCounter(val sql: String) {
    val mode: String = SqlNormaliser.mode(sql)
    val table: String = SqlNormaliser.table(sql)
    val calls = LongAdder()
    val rows = LongAdder()
    val driverCpuNs = LongAdder()
    val mappingCpuNs = LongAdder()

    /** The Spring Data entity (read: class of the mapped rows; write: converted entity), else the table. */
    @Volatile
    var entity: String? = null

    val driver = Account { driverCpuNs.add(it) }
    val mapping = Account { mappingCpuNs.add(it) }

    internal fun toJson(): Map<String, Any> {
        val e = entity ?: table
        return linkedMapOf(
            "key" to "$mode $e ${"%08x".format(sql.hashCode())}", "sql" to sql, "entity" to e, "mode" to mode,
            "calls" to calls.sum(), "rows" to rows.sum(),
            "driverCpuNs" to driverCpuNs.sum(), "mappingCpuNs" to mappingCpuNs.sum(),
        )
    }
}

/** SQL text -> statement key (literals and bind markers as `?`, lists collapsed), mode and main table. */
object SqlNormaliser {
    private val whitespace = Regex("\\s+")
    private val strings = Regex("'(?:[^']|'')*'")
    private val markers = Regex("\\$\\d+|(?<!:):[A-Za-z_]\\w*")
    private val numbers = Regex("(?<![\\w.\$])\\d+(?:\\.\\d+)?(?![\\w.])")
    private val lists = Regex("\\(\\s*\\?(?:\\s*,\\s*\\?)+\\s*\\)")
    private val repeatedGroups = Regex("(\\((?:\\?|\\?\\.\\.\\.)\\))(?:\\s*,\\s*\\1)+")
    private val locks = Regex("\\bFOR\\s+(?:NO\\s+KEY\\s+)?(?:UPDATE|SHARE)\\b", RegexOption.IGNORE_CASE)
    private val writes = Regex("\\b(?:INSERT|UPDATE|DELETE|MERGE|TRUNCATE)\\b", RegexOption.IGNORE_CASE)
    private val tables = Regex("\\b(?:FROM|INTO|UPDATE)\\s+(\"?[A-Za-z_][\\w.]*\"?)", RegexOption.IGNORE_CASE)

    fun normalise(sql: String): String {
        var s = whitespace.replace(sql.trim(), " ")
        s = strings.replace(s, "?")
        s = markers.replace(s, "?")
        s = numbers.replace(s, "?")
        s = lists.replace(s, "(?...)")
        s = repeatedGroups.replace(s, "$1, ...")
        return if (s.length > 2000) s.substring(0, 2000) + "..." else s
    }

    fun mode(normalised: String): String =
        if (writes.containsMatchIn(locks.replace(normalised, " "))) "write" else "read"

    fun table(normalised: String): String =
        tables.find(normalised)?.groupValues?.get(1)?.trim('"')?.lowercase() ?: "?"
}
