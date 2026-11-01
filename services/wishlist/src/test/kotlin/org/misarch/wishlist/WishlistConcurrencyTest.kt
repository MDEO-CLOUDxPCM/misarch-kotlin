package org.misarch.wishlist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.persistence.requiredId
import org.misarch.common.testing.MisarchIntegrationTest
import org.misarch.wishlist.event.model.ProductVariantDTO
import org.misarch.wishlist.event.model.UserDTO
import org.misarch.wishlist.graphql.input.CreateWishlistInput
import org.misarch.wishlist.graphql.input.UpdateWishlistInput
import org.misarch.wishlist.persistence.repository.WishlistRepository
import org.misarch.wishlist.persistence.repository.WishlistToProductVariantRepository
import org.misarch.wishlist.service.ProductVariantService
import org.misarch.wishlist.service.UserService
import org.misarch.wishlist.service.WishlistService
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Concurrent writes of one wishlist are applied one after the other (the wishlist row is locked first): the last
 * writer wins, nothing fails on the link table's key or foreign key.
 */
class WishlistConcurrencyTest : MisarchIntegrationTest() {

    @Autowired
    lateinit var wishlistService: WishlistService

    @Autowired
    lateinit var userService: UserService

    @Autowired
    lateinit var productVariantService: ProductVariantService

    @Autowired
    lateinit var wishlistRepository: WishlistRepository

    @Autowired
    lateinit var linkRepository: WishlistToProductVariantRepository

    private val admin = AuthorizedUser(UUID.randomUUID(), listOf("admin"))

    private fun variants(count: Int): List<UUID> = runBlocking {
        (1..count).map { UUID.randomUUID().also { productVariantService.registerProductVariant(ProductVariantDTO(it)) } }
    }

    private fun newWishlist(variants: List<UUID>): UUID = runBlocking {
        val user = UUID.randomUUID().also { userService.registerUser(UserDTO(it)) }
        wishlistService.createWishlist(CreateWishlistInput(user, variants, "w")).requiredId
    }

    private fun variantsOf(id: UUID): Set<UUID> = runBlocking {
        linkRepository.findByKeyWishlistId(id).map { it.key.productVariantId }.toSet()
    }

    @Test
    fun `concurrent updates with overlapping variant sets - last writer wins`() {
        val shared = variants(1).single()
        repeat(30) {
            val id = newWishlist(listOf(shared))
            val a = listOf(shared) + variants(1)
            val b = listOf(shared) + variants(2)
            runBlocking {
                listOf(a, b).map { set ->
                    async(Dispatchers.IO) { wishlistService.updateWishlist(UpdateWishlistInput(id, set, null), admin) }
                }.awaitAll()
            }
            assertThat(variantsOf(id)).isIn(a.toSet(), b.toSet())
        }
    }

    @Test
    fun `an update racing a delete - the wishlist is gone or updated, never half`() {
        repeat(30) {
            val id = newWishlist(variants(1))
            val set = variants(2)
            val results = runBlocking {
                listOf(
                    async(Dispatchers.IO) { runCatching { wishlistService.updateWishlist(UpdateWishlistInput(id, set, null), admin) } },
                    async(Dispatchers.IO) { runCatching { wishlistService.deleteWishlist(id, admin) } },
                ).awaitAll()
            }
            // the delete always succeeds; the update succeeds before it or finds no wishlist after it
            assertThat(results[1].isSuccess).isTrue()
            results[0].exceptionOrNull()?.let { assertThat(it).isInstanceOf(NoSuchElementException::class.java) }
            assertThat(runBlocking { wishlistRepository.findById(id) }).isNull()
            assertThat(variantsOf(id)).isEmpty()
        }
    }
}
