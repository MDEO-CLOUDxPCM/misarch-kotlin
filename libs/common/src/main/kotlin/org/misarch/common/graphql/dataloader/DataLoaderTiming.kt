package org.misarch.common.graphql.dataloader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import org.misarch.common.timing.TimingSpans
import org.misarch.common.timing.counters.CpuCounters
import java.util.concurrent.CompletableFuture

/**
 * The timing hooks of a data loader batch, for [BaseIdDataLoader] and for data loaders of their own (e.g. payment's
 * `PaymentInformationDataLoader`, keyed by String): a batch launched with [launch] is the counter operation
 * `dataloader <dataLoaderName>`, its load in [load] the span `dataloader <entity>` (docs/TIMING-COUNTERS.md). Both
 * are off by default and then just run the block.
 */
object DataLoaderTiming {

    /**
     * `scope.future(block)`; with the timing counters on ([CpuCounters]) the batch is the operation
     * `dataloader <dataLoaderName>` (its coroutine is bound to it, see `RequestCpuElement`). Without it, the batch's
     * coroutine would be bound to whatever frame is current when the loader is dispatched.
     */
    fun <R> launch(scope: CoroutineScope, dataLoaderName: String, block: suspend CoroutineScope.() -> R): CompletableFuture<R> {
        if (!CpuCounters.enabled) return scope.future(block = block)
        val op = CpuCounters.operation("dataloader", dataLoaderName)
        op.calls.increment()
        return CpuCounters.explicit(op) { scope.future(block = block) }
    }

    /** [block] (the batch's load) in a timing span `dataloader <entity>` ([TimingSpans]; while off just [block]). */
    suspend fun <R : Collection<*>> load(entity: String, batchSize: Int, block: suspend () -> R): R = TimingSpans.span(
        { "dataloader $entity" },
        {
            it.setAttribute(TimingSpans.ENTITY, entity)
                .setAttribute(TimingSpans.ACCESS, TimingSpans.READ)
                .setAttribute(TimingSpans.BATCH_SIZE, batchSize.toLong())
        },
        { rows -> rows.size.toLong() },
    ) { block() }
}
