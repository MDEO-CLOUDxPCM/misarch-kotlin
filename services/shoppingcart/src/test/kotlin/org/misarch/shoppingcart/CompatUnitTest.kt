package org.misarch.shoppingcart

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.rust.asyncgraphql.RustOffsetDateTime
import org.misarch.common.rust.RustUuid
import org.misarch.shoppingcart.graphql.model.CommonOrderInput
import org.misarch.shoppingcart.graphql.model.OrderDirection
import org.misarch.shoppingcart.graphql.model.ProductVariant
import org.misarch.shoppingcart.graphql.model.ShoppingCart
import org.misarch.shoppingcart.graphql.model.ShoppingCartItem
import org.misarch.common.graphql.GraphQLErrorException
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.util.UUID

/**
 * Unit tests of the service-specific Rust semantics (in-memory pagination). The shared
 * async-graphql/serde/uuid/chrono helpers are tested in libs/common (`AsyncGraphqlCompatTest`).
 */
class CompatUnitTest {

    @Test
    fun `in-memory pagination of shopping cart items`() {
        val ids = listOf(
            "f0000000-0000-4000-8000-000000000000", "10000000-0000-4000-8000-000000000000",
            "80000000-0000-4000-8000-000000000000", "7fffffff-0000-4000-8000-000000000000",
            "00000000-0000-4000-8000-000000000001"
        ).map(UUID::fromString)
        val cart = ShoppingCart(
            RustOffsetDateTime.now(),
            ids.map { ShoppingCartItem(it, 1, RustOffsetDateTime.now(), ProductVariant(it)) }
        )
        val sorted = ids.sortedWith(RustUuid.ORDER)
        fun page(first: Int?, skip: Int?, direction: OrderDirection? = null) =
            cart.shoppingcartItems(first, skip, direction?.let { CommonOrderInput(it) })

        page(null, null).let {
            assertThat(it.nodes.map { n -> n.id }).isEqualTo(sorted)
            assertThat(it.totalCount).isEqualTo(5)
            assertThat(it.hasNextPage).isFalse()
        }
        page(2, 1, OrderDirection.DESC).let {
            assertThat(it.nodes.map { n -> n.id }).isEqualTo(sorted.reversed().subList(1, 3))
            assertThat(it.hasNextPage).isTrue()
        }
        page(0, null).let {
            assertThat(it.nodes).isEmpty()
            assertThat(it.hasNextPage).isTrue()
        }
        page(null, 10).let {
            assertThat(it.nodes).isEmpty()
            assertThat(it.hasNextPage).isFalse()
        }
        page(2, 3).let {
            assertThat(it.nodes.map { n -> n.id }).isEqualTo(sorted.subList(3, 5))
            assertThat(it.hasNextPage).isFalse()
        }
        assertThatThrownBy { page(-1, null) }.isInstanceOf(GraphQLErrorException::class.java)
            .hasMessage("Failed to parse \"Int\": Invalid number")
        assertThatThrownBy { page(null, -1) }.hasMessage("Failed to parse \"Int\": Invalid number")
    }
}
