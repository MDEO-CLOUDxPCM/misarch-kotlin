package org.misarch.common.timing.counters

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The coroutine context element of one request (put into the request's coroutine context by
 * [TimingCountersWebFilter], so every coroutine of the request - the handler, graphql-kotlin's execution scope, the
 * data fetchers' and data loaders' coroutines - carries it). On every resume of a coroutine on a thread
 * ([updateThreadContext]) it pushes a frame for that coroutine's operation and on every suspension
 * ([restoreThreadContext]) pops it, so the coroutine's CPU between resume and suspend goes to the operation.
 *
 * The operation of a coroutine is bound at its first resume:
 * 1. a coroutine whose parent job is bound inherits the parent's operation (`async`, `withContext`, `coroutineScope`);
 * 2. else, if it starts inside an explicit frame (a data fetcher's or data loader's synchronous wrapper: the
 *    request runs on `Dispatchers.Unconfined`, so `scope.future {}` starts the coroutine right there), it gets that
 *    frame's operation;
 * 3. else the nearest operation frame on the thread, else the request's own (HTTP handler) account.
 */
class RequestCpuElement(private val request: Account) :
    AbstractCoroutineContextElement(Key), ThreadContextElement<Int> {

    companion object Key : CoroutineContext.Key<RequestCpuElement>

    private val bound = ConcurrentHashMap<Job, Account>()

    override fun updateThreadContext(context: CoroutineContext): Int {
        val job = context[Job]
        val account = if (job == null) CpuCounters.topOperation() ?: request else bound[job] ?: bind(job)
        return CpuCounters.enter(account, CpuCounters.COROUTINE)
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Int) = CpuCounters.exitTo(oldState)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun bind(job: Job): Account {
        val parent = job.parent?.let { bound[it] }
        val account = parent ?: CpuCounters.topExplicit() ?: CpuCounters.topOperation() ?: request
        return bound.putIfAbsent(job, account) ?: account
    }
}
