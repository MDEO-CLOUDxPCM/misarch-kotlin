package org.misarch.common.nest

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * What happens when a fire-and-forget task fails. The original (Node 18, no `unhandledRejection` handler) terminates
 * the process on such an unhandled promise rejection; Docker/k8s restarts it. Default: same (`halt(1)`);
 * `MISARCH_EXIT_ON_UNHANDLED_ERROR=false` only logs.
 */
fun interface ProcessTerminator {
    fun terminate(error: Throwable)
}

/**
 * Registered by [NestWebConfiguration] (bean `defaultProcessTerminator`, [exit] from [EXIT_PROPERTY]); a `@Primary`
 * [ProcessTerminator] bean replaces it.
 */
class DefaultProcessTerminator(private val exit: Boolean) : ProcessTerminator {
    private val logger = LoggerFactory.getLogger(DefaultProcessTerminator::class.java)

    override fun terminate(error: Throwable) {
        if (!exit) return
        logger.error("Unhandled error in a fire-and-forget task, terminating like the original Node process")
        System.out.flush()
        System.err.flush()
        Runtime.getRuntime().halt(1)
    }

    companion object {
        /** `misarch.exit-on-unhandled-error`, else env `MISARCH_EXIT_ON_UNHANDLED_ERROR`, default true. */
        const val EXIT_PROPERTY = "\${misarch.exit-on-unhandled-error:\${MISARCH_EXIT_ON_UNHANDLED_ERROR:true}}"
    }
}

/**
 * Runs work that the original started without awaiting it (`this.service.doSomething()` without `await`): the HTTP
 * answer does not wait for it, and a failure is an unhandled rejection ([ProcessTerminator]).
 * Registered by [NestWebConfiguration] (bean `detachedTasks`).
 */
class DetachedTasks(private val terminator: ProcessTerminator) {
    private val logger = LoggerFactory.getLogger(DetachedTasks::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun launch(name: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Throwable) {
                logger.error("Unhandled rejection in {}: {}", name, e.message, e)
                terminator.terminate(e)
            }
        }
    }
}
