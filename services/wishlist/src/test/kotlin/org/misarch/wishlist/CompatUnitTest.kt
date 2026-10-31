package org.misarch.wishlist

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.common.graphql.GraphQLErrorException
import org.misarch.common.rust.asyncgraphql.RustOffsetDateTime
import org.misarch.wishlist.graphql.model.User
import org.misarch.wishlist.graphql.model.Wishlist
import org.misarch.wishlist.persistence.WishlistRepository
import java.util.UUID

/**
 * Unit tests of the service-specific Rust quirks. The pagination of `productVariants` (SQL connection) is tested in
 * [WishlistIntegrationTest]; the shared async-graphql/serde/uuid/chrono helpers in libs/common
 * (`AsyncGraphqlCompatTest`).
 */
class CompatUnitTest {

    @Test
    fun `productVariants rejects negative paging arguments before any statement`() {
        val repository = mockk<WishlistRepository>() // strict: any call fails the test
        val wishlist = Wishlist(UUID.randomUUID(), User(UUID.randomUUID()), "w", RustOffsetDateTime.now(), RustOffsetDateTime.now())
        assertThatThrownBy { wishlist.productVariants(-1, null, null, repository) }.isInstanceOf(GraphQLErrorException::class.java)
            .hasMessage("Failed to parse \"Int\": Invalid number")
        assertThatThrownBy { wishlist.productVariants(null, -1, null, repository) }.hasMessage("Failed to parse \"Int\": Invalid number")
    }
}
