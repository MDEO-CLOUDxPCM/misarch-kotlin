package org.misarch.shoppingcart

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.common.graphql.GraphQLErrorException
import org.misarch.common.rust.asyncgraphql.RustOffsetDateTime
import org.misarch.shoppingcart.graphql.model.ShoppingCart
import org.misarch.shoppingcart.persistence.ShoppingcartRepository
import java.util.UUID

/**
 * Unit tests of the service-specific Rust semantics. The pagination of `shoppingcartItems` (SQL connection) is tested
 * in [ShoppingcartIntegrationTest]; the shared async-graphql/serde/uuid/chrono helpers in libs/common
 * (`AsyncGraphqlCompatTest`).
 */
class CompatUnitTest {

    @Test
    fun `shoppingcartItems rejects negative paging arguments before any statement`() {
        val repository = mockk<ShoppingcartRepository>() // strict: any call fails the test
        val cart = ShoppingCart(UUID.randomUUID(), RustOffsetDateTime.now())
        assertThatThrownBy { cart.shoppingcartItems(-1, null, null, repository) }.isInstanceOf(GraphQLErrorException::class.java)
            .hasMessage("Failed to parse \"Int\": Invalid number")
        assertThatThrownBy { cart.shoppingcartItems(null, -1, null, repository) }.hasMessage("Failed to parse \"Int\": Invalid number")
    }
}
