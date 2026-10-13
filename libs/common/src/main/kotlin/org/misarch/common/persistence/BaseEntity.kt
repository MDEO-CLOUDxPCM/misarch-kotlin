package org.misarch.common.persistence

import java.util.UUID

/**
 * An entity (table row) that can be converted to its GraphQL type.
 *
 * @param T the GraphQL (DTO) type
 */
interface BaseEntity<T> {

    /** The primary key; null before the first insert when the database generates it. */
    val id: UUID?

    /** Converts the entity to its GraphQL type. */
    fun toDTO(): T
}
