package org.misarch.common.persistence

import java.util.UUID

/**
 * An entity (table row) with a UUID primary key.
 */
interface IdEntity {

    /** The primary key; null before the first insert when the database generates it. */
    val id: UUID?
}

/**
 * An entity (table row) that can be converted to its GraphQL type.
 *
 * @param T the GraphQL (DTO) type
 */
interface BaseEntity<T> : IdEntity {

    /** Converts the entity to its GraphQL type. */
    fun toDTO(): T
}

/** The primary key of a stored entity; fails before its first insert (when the database generates it). */
val IdEntity.requiredId: UUID
    get() = checkNotNull(id) { "${this::class.simpleName} has no id before its first insert" }
