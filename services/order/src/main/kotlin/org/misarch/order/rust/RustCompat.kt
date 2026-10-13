package org.misarch.order.rust

import java.math.BigInteger
import java.util.UUID

/**
 * Helpers that reproduce observable behaviour of the Rust original (async-graphql, serde, chrono, bson, uuid).
 * See docs/specs/order.md. UUID parsing/ordering and timestamps are shared with the other Rust ports in libs/common:
 * `org.misarch.common.rust.RustUuid.tryParse` / `RustUuid.ORDER`, `org.misarch.common.rust.RustDateTime`
 * (`now()`, `toRfc3339()` = GraphQL `DateTime`, `toSerde()` = event payloads).
 */

/** Rust `std::any::type_name` strings that appear in the original's error messages. */
object RustTypeNames {
    const val ORDER = "misarch_order::graphql::model::order::Order"
    const val ORDER_ITEM = "misarch_order::graphql::model::order_item::OrderItem"
    const val USER = "misarch_order::graphql::model::user::User"
    const val SHIPMENT_METHOD = "misarch_order::graphql::model::foreign_types::ShipmentMethod"
    const val COUPON = "misarch_order::graphql::model::foreign_types::Coupon"
    const val TAX_RATE = "misarch_order::graphql::model::foreign_types::TaxRate"
    const val UUID = "bson::uuid::Uuid"
    const val U64 = "u64"
    const val UUID_U64_TUPLE = "(bson::uuid::Uuid, u64)"

    /** `HashMap<Uuid, V>` */
    fun hashMap(valueTypeName: String) = "std::collections::hash::map::HashMap<$UUID, $valueTypeName>"
}

/** Error messages of the original, built from the same format strings. */
object RustMessages {
    const val AUTHENTICATION_MISSING =
        "Authentication failed. Authorized-User header is not set or could not be parsed."
    const val OUT_OF_RANGE = "out of range integral type conversion attempted"
    const val ERROR_DECODING_RESPONSE_BODY = "error decoding response body"

    fun notPermitted(headerUserId: UUID) =
        "Authentication failed for user of UUID: `$headerUserId`. Operation not permitted."

    /** `query_object`. */
    fun notFound(typeName: String, id: UUID) = "$typeName with UUID: `$id` not found."

    /** `validate_objects`. */
    fun notPresent(typeName: String, id: UUID) = "$typeName with UUID: `$id` is not present in the system."

    /** `build_hash_map_error` (note the trailing space). */
    fun hashMapError(valueTypeName: String, id: UUID) =
        "`$valueTypeName` for product variant of UUID: `$id` is not present in `${RustTypeNames.hashMap(valueTypeName)}`. "

    /** reqwest: transport error. */
    fun errorSendingRequest(url: String) = "error sending request for url ($url)"
}

/** Unsigned 64 bit (`u64`) arithmetic on the bit pattern held in a Long. */
object U64 {
    /** `a >= b` as u64. */
    fun ge(a: Long, b: Long) = java.lang.Long.compareUnsigned(a, b) >= 0

    /** Rust `i64::try_from(u64)`. */
    fun toI64(value: Long): Long = if (value < 0) throw RustError(RustMessages.OUT_OF_RANGE) else value

    /** Rust `u64::try_from(i64)`. */
    fun fromI64(value: Long): Long = if (value < 0) throw RustError(RustMessages.OUT_OF_RANGE) else value

    /** Rust `f64 as u64` (saturating, NaN -> 0); values >= 2^63 are clamped to Long.MAX_VALUE (cannot occur). */
    fun fromF64(value: Double): Long = when {
        value.isNaN() || value <= 0.0 -> 0L
        value >= 9.223372036854775807E18 -> Long.MAX_VALUE
        else -> value.toLong()
    }

    /** JSON number of a u64 (serde writes the unsigned value). */
    fun json(value: Long): Number = if (value >= 0) value else BigInteger(java.lang.Long.toUnsignedString(value))

    /** GraphQL `Int` output of a u64 (async-graphql writes any u64; graphql-java's Int is 32 bit). */
    fun graphqlInt(value: Long): Int =
        if (value in 0..Int.MAX_VALUE.toLong()) value.toInt()
        else throw RustError("Int cannot represent non 32-bit signed integer value: ${java.lang.Long.toUnsignedString(value)}")
}

/** An error whose message is returned verbatim (GraphQL error message, see `GraphQLErrorException`). */
class RustError(message: String) : org.misarch.common.graphql.GraphQLErrorException(message)
