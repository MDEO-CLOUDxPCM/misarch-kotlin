package org.misarch.order

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.order.auth.AuthorizedUserHeader
import org.misarch.order.graphql.model.OrderDirection
import org.misarch.order.graphql.model.OrderOrderField
import org.misarch.order.rust.RustError
import org.misarch.order.rust.RustMessages
import org.misarch.order.rust.RustTypeNames
import org.misarch.order.rust.U64
import java.util.UUID

/**
 * Unit tests of the order-specific Rust compatibility helpers (u64, header parsing, messages, pagination, sort keys).
 * UUID parsing/ordering and timestamp formats are shared and tested in libs/common (`RustUuidAndTimeTest`).
 */
class RustCompatTest {

    @Test
    fun `u64 helpers`() {
        assertThat(U64.ge(-1L, 5L)).isTrue() // u64::MAX >= 5
        assertThat(U64.ge(5L, -1L)).isFalse()
        assertThat(U64.fromF64(Double.NaN)).isEqualTo(0)
        assertThat(U64.fromF64(-3.5)).isEqualTo(0)
        assertThat(U64.fromF64(19.99)).isEqualTo(19)
        assertThatThrownBy { U64.toI64(-1L) }.hasMessage(RustMessages.OUT_OF_RANGE)
        assertThatThrownBy { U64.fromI64(-1L) }.hasMessage(RustMessages.OUT_OF_RANGE)
        assertThat(U64.json(-1L).toString()).isEqualTo("18446744073709551615")
        assertThat(U64.graphqlInt(42)).isEqualTo(42)
        assertThatThrownBy { U64.graphqlInt(Int.MAX_VALUE.toLong() + 1) }.isInstanceOf(RustError::class.java)
    }

    @Test
    fun `rust messages`() {
        val id = UUID.fromString("67e55044-10b1-426f-9247-bb680e5fe0c8")
        assertThat(RustMessages.notFound(RustTypeNames.ORDER, id))
            .isEqualTo("misarch_order::graphql::model::order::Order with UUID: `67e55044-10b1-426f-9247-bb680e5fe0c8` not found.")
        assertThat(RustMessages.hashMapError(RustTypeNames.UUID_U64_TUPLE, id)).isEqualTo(
            "`(bson::uuid::Uuid, u64)` for product variant of UUID: `67e55044-10b1-426f-9247-bb680e5fe0c8` is not present in " +
                "`std::collections::hash::map::HashMap<bson::uuid::Uuid, (bson::uuid::Uuid, u64)>`. "
        )
        assertThat(RustMessages.notPresent(RustTypeNames.COUPON, id)).isEqualTo(
            "misarch_order::graphql::model::foreign_types::Coupon with UUID: `67e55044-10b1-426f-9247-bb680e5fe0c8` is not present in the system."
        )
    }

    @Test
    fun `authorized user header follows serde rules`() {
        val id = UUID.randomUUID()
        assertThat(AuthorizedUserHeader.parse("""{"id":"$id","roles":["buyer","admin"],"extra":1}""")!!.roles)
            .containsExactly("buyer", "admin")
        assertThat(AuthorizedUserHeader.parse("""{"id":"$id","roles":[]}""")!!.id).isEqualTo(id)
        assertThat(AuthorizedUserHeader.parse("""{"id":"$id","roles":["Buyer"]}""")).isNull()
        assertThat(AuthorizedUserHeader.parse("""{"id":"$id","roles":["buyer","superuser"]}""")).isNull()
        assertThat(AuthorizedUserHeader.parse("""{"id":"$id"}""")).isNull()
        assertThat(AuthorizedUserHeader.parse("""{"id":"nope","roles":[]}""")).isNull()
        assertThat(AuthorizedUserHeader.parse("undefined")).isNull()
        assertThat(AuthorizedUserHeader.parse("")).isNull()
        assertThat(AuthorizedUserHeader.parse(null)).isNull()
        val user = AuthorizedUserHeader.parse("""{"roles":["employee"],"id":"$id","name":"x"}""")!!
        assertThat(AuthorizedUserHeader.serialize(user)).isEqualTo("""{"id":"$id","roles":["employee"]}""")
    }

    @Test
    fun `order sort keys reproduce the mongo sort with id desc tiebreak`() {
        assertThat(OrderOrderField.ID.sql(OrderDirection.ASC)).isEqualTo("id ASC")
        assertThat(OrderOrderField.ID.sql(OrderDirection.DESC)).isEqualTo("id DESC")
        assertThat(OrderOrderField.CREATED_AT.sql(OrderDirection.ASC)).isEqualTo("created_at ASC, id DESC")
        assertThat(OrderOrderField.USER_ID.sql(OrderDirection.ASC)).isEqualTo("user_id ASC, id DESC")
        assertThat(OrderOrderField.NAME.sql(OrderDirection.ASC)).isEqualTo("id DESC")
        assertThat(OrderOrderField.LAST_UPDATED_AT.sql(OrderDirection.DESC)).isEqualTo("id DESC")
    }
}
