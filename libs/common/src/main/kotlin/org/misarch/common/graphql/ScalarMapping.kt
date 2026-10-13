package org.misarch.common.graphql

import graphql.schema.GraphQLScalarType
import kotlin.reflect.KClass

/**
 * Maps a Kotlin/Java class to a GraphQL scalar. Every bean of this type is added to the schema generator hooks.
 * Defaults (always present): `UUID` -> `UUID` (extended scalars), `OffsetDateTime` -> `DateTime`,
 * `LocalDate` -> `Date`. Register a bean to add further scalars (or to override a default by class).
 */
data class ScalarMapping(val kClass: KClass<*>, val scalar: GraphQLScalarType)
