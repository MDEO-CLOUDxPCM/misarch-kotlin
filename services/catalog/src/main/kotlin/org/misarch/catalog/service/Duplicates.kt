package org.misarch.catalog.service

/**
 * Returns a set of all elements that occur more than once in this collection.
 *
 * @return a set of all duplicate elements.
 */
internal fun <T> Iterable<T>.duplicates(): Set<T> {
    return groupingBy { it }.eachCount().filter { it.value > 1 }.keys
}