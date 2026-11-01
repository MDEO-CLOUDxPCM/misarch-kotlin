package org.misarch.common.remote

import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Runs [block] and retries it after a failure: at most `retries + 1` attempts, with a pause of [delay] after each
 * failed attempt but the last. The exception of the last attempt propagates; a cancellation is never retried.
 *
 * ```
 * withRetries(3, 500.milliseconds, { attempt, e -> logger.warn("Attempt {} failed", attempt, e) }) {
 *     webClient.post().uri(endpoint).bodyValue(body).retrieve().toBodilessEntity().awaitSingle()
 * }
 * ```
 *
 * @param retries the number of retries after the first attempt (0: a single attempt)
 * @param delay the pause after a failed attempt
 * @param onRetry called with the number of the failed attempt (1-based) and its exception before each retry
 * @param block the call
 * @return the result of the first successful attempt
 */
suspend fun <T> withRetries(
    retries: Int,
    delay: Duration,
    onRetry: (attempt: Int, exception: Exception) -> Unit = { _, _ -> },
    block: suspend () -> T,
): T {
    var attempt = 1
    while (true) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (attempt > retries) {
                throw e
            }
            onRetry(attempt, e)
            delay(delay)
            attempt++
        }
    }
}
