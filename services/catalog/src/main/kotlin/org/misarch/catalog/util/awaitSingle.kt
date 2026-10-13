package org.misarch.catalog.util

/**
 * Returns this value or throws the exception the original service threw for a missing row: the original called
 * `repository.findById(id).awaitSingle()` (kotlinx.coroutines.reactor `Mono.awaitSingle`), which fails on an empty result
 * with a [NoSuchElementException] without message: the GraphQL error reads `Exception while fetching data (/x) : null`
 * (verified on the running original, docs/INTEGRATION.md F3).
 *
 * @return this value, if not null
 * @throws NoSuchElementException if this value is null
 */
fun <T : Any> T?.orNoValueReceived(): T {
    return this ?: throw NoSuchElementException()
}
