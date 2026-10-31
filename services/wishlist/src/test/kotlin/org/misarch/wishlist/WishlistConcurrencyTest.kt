package org.misarch.wishlist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MisarchIntegrationTest
import org.misarch.wishlist.persistence.WishlistRepository
import org.misarch.wishlist.persistence.WishlistRow
import org.springframework.beans.factory.annotation.Autowired
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Concurrent writes of one wishlist behave like the original's single-document `$set` / `delete_one`: the last
 * writer wins, nothing fails. Before the wishlist row was locked first, two updates with overlapping variant sets
 * failed on the link table's primary key, disjoint ones left the union, and an update racing a delete hit the
 * foreign key (docs/RELATIONAL-PORT.md).
 */
class WishlistConcurrencyTest : MisarchIntegrationTest() {

    @Autowired
    lateinit var repository: WishlistRepository

    private val now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

    private fun newWishlist(variants: List<UUID>): UUID = runBlocking {
        val user = UUID.randomUUID().also { repository.insertUser(it) }
        UUID.randomUUID().also { repository.insertWishlist(WishlistRow(it, user, "w", now, now), variants) }
    }

    private fun variantsOf(id: UUID): Set<UUID> = runBlocking {
        repository.productVariantsOfWishlist(id, null, 0, true).nodes().toSet()
    }

    @Test
    fun `concurrent updates with overlapping variant sets - last writer wins`() {
        val shared = UUID.randomUUID()
        repeat(30) {
            val id = newWishlist(listOf(shared))
            val a = listOf(shared, UUID.randomUUID())
            val b = listOf(shared, UUID.randomUUID(), UUID.randomUUID())
            runBlocking {
                listOf(a, b).map { set -> async(Dispatchers.IO) { repository.updateProductVariants(id, set, now) } }.awaitAll()
            }
            assertThat(variantsOf(id)).isIn(a.toSet(), b.toSet())
        }
    }

    @Test
    fun `an update racing a delete - both succeed, the wishlist is gone or updated`() {
        repeat(30) {
            val id = newWishlist(listOf(UUID.randomUUID()))
            val set = listOf(UUID.randomUUID(), UUID.randomUUID())
            runBlocking {
                listOf(
                    async(Dispatchers.IO) { repository.updateProductVariants(id, set, now) },
                    async(Dispatchers.IO) { repository.deleteWishlist(id) },
                ).awaitAll()
            }
            assertThat(runBlocking { repository.findWishlist(id) }).isNull()
            assertThat(variantsOf(id)).isEmpty()
        }
    }
}
